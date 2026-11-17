package com.samius.multicam

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.samius.multicam.databinding.ActivityControllerBinding
import com.samius.multicam.lan.DiscoveryProtocol
import com.samius.multicam.lan.LanCameraClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controller role: discovers every "camera" role phone on the current LAN
 * (UDP broadcast, see DiscoveryProtocol) and controls them directly over
 * HTTP, with no internet or remote server involved. Mirrors the remote
 * control page's grid: per-camera start/stop plus start/stop all.
 */
class ControllerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControllerBinding
    private lateinit var adapter: CameraListAdapter
    private val lanClient = LanCameraClient()
    private val handler = Handler(Looper.getMainLooper())

    private val cameras = linkedMapOf<String, ControllerCameraState>()
    @Volatile private var scanning = false
    @Volatile private var polling = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControllerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = CameraListAdapter { cam, recording ->
            sendCommandWithConfirm(cam, recording)
        }
        binding.cameraList.layoutManager = LinearLayoutManager(this)
        binding.cameraList.adapter = adapter

        binding.rescanButton.setOnClickListener { scan() }
        binding.settingsButton.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        binding.startAllButton.setOnClickListener { commandAll(true) }
        binding.stopAllButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Zastavit vše?")
                .setMessage("Opravdu zastavit nahrávání na všech nalezených kamerách?")
                .setPositiveButton("Zastavit") { _, _ -> commandAll(false) }
                .setNegativeButton("Zrušit", null)
                .show()
        }

        scan()
        startPollLoop()
    }

    private fun scan() {
        if (scanning) return
        scanning = true
        binding.scanStatusText.text = "Hledám kamery v síti…"
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { DiscoveryProtocol.discoverOnce(2000, this@ControllerActivity) }
            found.forEach { d ->
                val existing = cameras[d.id]
                if (existing != null) {
                    existing.address = d.address
                    existing.lanPort = d.lanPort
                    existing.recording = d.recording
                    existing.lastSeenMs = System.currentTimeMillis()
                } else {
                    cameras[d.id] = ControllerCameraState(
                        id = d.id, name = d.name, address = d.address,
                        lanPort = d.lanPort, recording = d.recording,
                    )
                }
            }
            adapter.submit(cameras.values.toList())
            binding.scanStatusText.text = if (cameras.isEmpty()) {
                "Žádná kamera nenalezena. Zkontroluj, že telefony jsou ve stejné Wi-Fi síti."
            } else {
                "Nalezeno kamer: ${cameras.size}"
            }
            scanning = false
        }
    }

    /** Periodically refreshes battery/thermal/recording status for already-known cameras. */
    private fun startPollLoop() {
        lifecycleScope.launch {
            while (polling) {
                withContext(Dispatchers.IO) {
                    cameras.values.toList().forEach { cam ->
                        try {
                            val status = lanClient.fetchStatus(cam.address, cam.lanPort)
                            cam.recording = status.optBoolean("recording", cam.recording)
                            cam.batteryPct = if (status.has("batteryPct")) status.optInt("batteryPct") else cam.batteryPct
                            cam.thermalStatus = status.optString("thermalStatus", cam.thermalStatus)
                            cam.lastSeenMs = System.currentTimeMillis()
                            cam.reachable = true
                        } catch (e: Exception) {
                            cam.reachable = false
                        }
                    }
                }
                adapter.submit(cameras.values.toList())
                kotlinx.coroutines.delay(1500)
            }
        }
    }

    private fun sendCommandWithConfirm(cam: ControllerCameraState, recording: Boolean) {
        if (!recording) {
            AlertDialog.Builder(this)
                .setTitle("Zastavit nahrávání?")
                .setMessage("Opravdu zastavit nahrávání: ${cam.name}?")
                .setPositiveButton("Zastavit") { _, _ -> sendCommand(cam, false) }
                .setNegativeButton("Zrušit", null)
                .show()
        } else {
            sendCommand(cam, true)
        }
    }

    private fun sendCommand(cam: ControllerCameraState, recording: Boolean) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { lanClient.sendCommand(cam.address, cam.lanPort, recording) }
                cam.recording = recording
                adapter.submit(cameras.values.toList())
            } catch (e: Exception) {
                Toast.makeText(this@ControllerActivity, "Příkaz selhal (${cam.name}): ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun commandAll(recording: Boolean) {
        cameras.values.forEach { sendCommand(it, recording) }
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!Config.isController()) {
            startActivity(android.content.Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
