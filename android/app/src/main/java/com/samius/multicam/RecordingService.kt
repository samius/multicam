package com.samius.multicam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.samius.multicam.net.ApiClient
import com.samius.multicam.net.Heartbeat
import com.samius.multicam.lan.CameraHttpServer
import com.samius.multicam.lan.DiscoveryProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service owning the camera + the control loop:
 *  - polls /api/state every ~1s, applies start/stop by cmdId (idempotent: ignores repeats)
 *  - posts /api/report heartbeat every ~2s
 *  - posts /api/preview every ~1.5s from the latest analyzed frame
 *  - restarts the recording segment every segmentMinutes while desiredRecording stays true
 *  - keeps running regardless of network state; desiredRecording persists across drops
 */
@OptIn(ExperimentalCamera2Interop::class)
class RecordingService : LifecycleService() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "multicam_recording"
        private const val NOTIF_ID = 1001

        const val ACTION_UPDATE_PREVIEW_SURFACE = "com.samius.multicam.UPDATE_PREVIEW_SURFACE"

        @Volatile var instance: RecordingService? = null
            private set
    }

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null
    private var previewJob: Job? = null

    private lateinit var apiClient: ApiClient
    private lateinit var cameraController: CameraController

    private var lastAppliedCmdId = -1
    private val currentSegmentFile = AtomicInteger(0)
    private var segmentJob: Job? = null

    private var boundNetwork: Network? = null
    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    private var lanHttpServer: CameraHttpServer? = null
    private var discoveryResponder: DiscoveryProtocol.Responder? = null
    private var localCmdId = AtomicInteger(0)
    private var localDesiredRecording = false

    var onStateChanged: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Připraveno"))

        apiClient = ApiClient(Config.serverUrl, Config.token)
        cameraController = CameraController(this, this)
        cameraController.setPreviewFrameListener { /* consumed by previewJob via latestPreview() */ }
        cameraController.start(null)

        bindToWifiIfAvailable()
        startLanServerAndDiscovery()

        scope = CoroutineScope(Dispatchers.IO + Job())
        startPolling()
        startHeartbeat()
        startPreviewUpload()
    }

    /**
     * Starts the embedded LAN HTTP server + UDP discovery responder. These run
     * independently of the remote server/internet connectivity, so a phone in
     * "controller" role on the same Wi-Fi/hotspot can find and control this
     * camera directly even with zero internet access.
     */
    private fun startLanServerAndDiscovery() {
        if (Config.isController()) return
        lanHttpServer = CameraHttpServer(
            statusProvider = { buildStatusJson() },
            previewProvider = { cameraController.latestPreview() },
            onCommand = { recording -> applyLocalCommand(recording) },
        )
        try {
            lanHttpServer?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            Log.e(TAG, "LAN HTTP server failed to start: ${e.message}")
        }

        discoveryResponder = DiscoveryProtocol.Responder { buildDiscoveryJson() }
        discoveryResponder?.start()
    }

    private fun buildStatusJson(): JSONObject {
        val battery = DeviceStats.battery(this)
        return JSONObject().apply {
            put("id", Config.deviceName)
            put("name", Config.deviceName)
            put("recording", cameraController.isRecording)
            put("cmdId", localCmdId.get())
            put("currentFileDurationSec", cameraController.currentDurationSec())
            battery.pct?.let { put("batteryPct", it) }
            battery.charging?.let { put("charging", it) }
            battery.tempC?.let { put("batteryTempC", it) }
            put("thermalStatus", DeviceStats.thermalStatus(this@RecordingService))
            put("freeSpaceBytes", StorageHelper.freeSpaceBytes(this@RecordingService))
            put("resolution", Config.resolution)
            put("fps", Config.fps)
            put("bitrateKbps", Config.bitrateKbps)
            put("currentFileName", cameraController.currentFileDescription)
        }
    }

    private fun buildDiscoveryJson(): JSONObject {
        return JSONObject().apply {
            put("id", Config.deviceName)
            put("name", Config.deviceName)
            put("lanPort", Config.LAN_PORT)
            put("recording", cameraController.isRecording)
        }
    }

    /** Applies a start/stop command received over the LAN (controller role), independent of the remote server's cmdId. */
    private fun applyLocalCommand(recording: Boolean) {
        localCmdId.incrementAndGet()
        localDesiredRecording = recording
        applyDesiredState(recording)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /** Lets MainActivity attach its PreviewView surface once the UI is visible. */
    fun attachPreviewSurface(surfaceProvider: androidx.camera.core.Preview.SurfaceProvider?) {
        cameraController.attachPreviewSurface(surfaceProvider)
    }

    private fun bindToWifiIfAvailable() {
        // Prefer routing all our HTTP traffic over Wi-Fi even if the phone has a mobile
        // data SIM that Samsung tries to "helpfully" fail over to when the Wi-Fi has no
        // internet (hotspot/local-only LAN case). Best-effort; safe to skip if unavailable.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    boundNetwork = network
                    try {
                        connectivityManager.bindProcessToNetwork(network)
                    } catch (e: Exception) {
                        Log.w(TAG, "bindProcessToNetwork failed: ${e.message}")
                    }
                }
                override fun onLost(network: Network) {
                    if (boundNetwork == network) {
                        boundNetwork = null
                        try {
                            connectivityManager.bindProcessToNetwork(null)
                        } catch (_: Exception) {}
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork(WIFI) failed: ${e.message}")
        }
    }

    private fun startPolling() {
        pollJob = scope?.launch {
            while (true) {
                try {
                    apiClient.update(Config.serverUrl, Config.token)
                    val state = apiClient.getState(Config.deviceName)
                    if (state.cmdId != lastAppliedCmdId) {
                        lastAppliedCmdId = state.cmdId
                        applyDesiredState(state.recording)
                    }
                } catch (e: Exception) {
                    // Network down or server unreachable: keep current recording state untouched.
                    Log.d(TAG, "poll failed (expected during network drop): ${e.message}")
                }
                delay(1000)
            }
        }
    }

    private fun applyDesiredState(shouldRecord: Boolean) {
        if (shouldRecord && !cameraController.isRecording) {
            beginRecordingWithSegmentation()
        } else if (!shouldRecord && cameraController.isRecording) {
            segmentJob?.cancel()
            cameraController.stopSegment()
        }
        updateNotification()
        onStateChanged?.invoke()
    }

    private fun beginRecordingWithSegmentation() {
        cameraController.startSegment(Config.deviceName) { desc, ok ->
            Log.i(TAG, "Segment finalized (ok=$ok) on $desc")
        }
        segmentJob?.cancel()
        segmentJob = scope?.launch {
            while (true) {
                delay(Config.segmentMinutes * 60_000L)
                if (!cameraController.isRecording) break
                // Roll to a new file. Short gap is acceptable per spec (audio resynced later).
                cameraController.stopSegment()
                cameraController.startSegment(Config.deviceName) { desc, ok ->
                    Log.i(TAG, "Segment finalized (ok=$ok) on $desc")
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope?.launch {
            while (true) {
                try {
                    val battery = DeviceStats.battery(this@RecordingService)
                    val thermal = DeviceStats.thermalStatus(this@RecordingService)
                    val free = StorageHelper.freeSpaceBytes(this@RecordingService)
                    val hb = Heartbeat(
                        id = Config.deviceName,
                        name = Config.deviceName,
                        recording = cameraController.isRecording,
                        currentFileDurationSec = cameraController.currentDurationSec(),
                        batteryPct = battery.pct,
                        charging = battery.charging,
                        batteryTempC = battery.tempC,
                        thermalStatus = thermal,
                        freeSpaceBytes = free,
                        resolution = Config.resolution,
                        fps = Config.fps,
                        bitrateKbps = Config.bitrateKbps,
                        currentFileName = cameraController.currentFileDescription,
                    )
                    apiClient.update(Config.serverUrl, Config.token)
                    apiClient.postReport(hb)
                } catch (e: Exception) {
                    Log.d(TAG, "heartbeat failed (expected during network drop): ${e.message}")
                }
                updateNotification()
                delay(2000)
            }
        }
    }

    private fun startPreviewUpload() {
        previewJob = scope?.launch {
            while (true) {
                try {
                    val jpeg = cameraController.latestPreview()
                    if (jpeg != null) {
                        apiClient.update(Config.serverUrl, Config.token)
                        apiClient.postPreview(Config.deviceName, jpeg)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "preview upload failed (expected during network drop): ${e.message}")
                }
                delay(1500)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Multicam nahrávání", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Multicam (${Config.deviceName})")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val text = if (cameraController.isRecording) {
            "REC ${cameraController.currentDurationSec()}s · ${cameraController.currentFileDescription}"
        } else {
            "Stop · čeká na příkaz"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    fun isRecording(): Boolean = cameraController.isRecording
    fun currentDurationSec(): Int = cameraController.currentDurationSec()
    fun lastError(): String? = cameraController.lastError

    override fun onDestroy() {
        pollJob?.cancel()
        heartbeatJob?.cancel()
        previewJob?.cancel()
        segmentJob?.cancel()
        discoveryResponder?.stop()
        try { lanHttpServer?.stop() } catch (_: Exception) {}
        cameraController.shutdown()
        instance = null
        super.onDestroy()
    }
}
