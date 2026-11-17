package com.samius.multicam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Wraps CameraX bind-to-lifecycle, manual Camera2 controls (ISO/exposure/WB),
 * segmented recording to the chosen storage target, and periodic preview JPEG capture.
 *
 * Segmentation note: CameraX's Recorder has no direct equivalent of
 * MediaRecorder.setNextOutputFile(), so segments are produced by stopping the
 * current Recording and immediately starting a new one. This causes a short
 * (sub-second, typically ~100-300ms) gap between files, which the handoff doc
 * explicitly says is acceptable since audio comes from the mixing desk and is
 * resynced in DaVinci Resolve.
 */
@ExperimentalCamera2Interop
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    companion object {
        private const val TAG = "CameraController"
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recorder: Recorder? = null
    private var activeRecording: Recording? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val segmentStartMs = AtomicLong(0L)
    private val currentFileDurationSec = AtomicLong(0L)

    @Volatile var isRecording: Boolean = false
        private set
    @Volatile var currentFileDescription: String = ""
        private set
    @Volatile var lastError: String? = null
        private set

    private var latestPreviewJpeg: ByteArray? = null
    private var onPreviewFrame: ((ByteArray) -> Unit)? = null

    fun setPreviewFrameListener(listener: (ByteArray) -> Unit) {
        onPreviewFrame = listener
    }

    fun currentDurationSec(): Int {
        if (!isRecording) return 0
        val start = segmentStartMs.get()
        if (start == 0L) return 0
        return ((System.currentTimeMillis() - start) / 1000).toInt()
    }

    /** Must be called on main thread. previewView may be null for a headless/dimmed start. */
    fun start(previewProvider: androidx.camera.core.Preview.SurfaceProvider?) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindUseCases(provider, previewProvider)
            } catch (e: Exception) {
                lastError = "init camera: ${e.message}"
                Log.e(TAG, "Camera init failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindUseCases(provider: ProcessCameraProvider, previewProvider: Preview.SurfaceProvider?) {
        provider.unbindAll()

        val width = Config.resolutionWidth()
        val height = Config.resolutionHeight()

        val quality = if (height >= 2160) Quality.UHD else Quality.FHD
        val qualitySelector = QualitySelector.from(quality)
        recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setExecutor(cameraExecutor)
            .build()
        videoCapture = VideoCapture.withOutput(recorder!!)

        val previewBuilder = Preview.Builder()
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(960, 540))

        // Manual Camera2 controls applied to the capture request builder used by CameraX.
        applyManualControls(previewBuilder)

        val preview = previewBuilder.build()
        previewProvider?.let { preview.setSurfaceProvider(it) }

        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(cameraExecutor) { imageProxy -> onAnalysisFrame(imageProxy) }
        imageAnalysis = analysis

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner, selector, preview, videoCapture, analysis
            )
        } catch (e: Exception) {
            lastError = "bindToLifecycle: ${e.message}"
            Log.e(TAG, "bindToLifecycle failed", e)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualControls(builder: Preview.Builder) {
        val ext = Camera2Interop.Extender(builder)
        if (Config.manualIso > 0) {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, Config.manualIso)
        }
        if (Config.manualExposureUs > 0) {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, Config.manualExposureUs * 1000L)
        }
        if (Config.manualWbKelvin > 0) {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            // Camera2 has no direct Kelvin knob; approximate via AWB lock + CCT is
            // sensor-specific. We lock AWB so it stays fixed once auto-converged,
            // which is the practical fix for stage-lighting color shifts between cuts.
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
    }

    private fun onAnalysisFrame(imageProxy: ImageProxy) {
        try {
            val jpeg = yuv420ToJpeg(imageProxy, quality = 50)
            if (jpeg != null) {
                latestPreviewJpeg = jpeg
                onPreviewFrame?.invoke(jpeg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "preview frame conversion failed: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun yuv420ToJpeg(imageProxy: ImageProxy, quality: Int): ByteArray? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return null
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), quality, out)
        return out.toByteArray()
    }

    fun latestPreview(): ByteArray? = latestPreviewJpeg

    /** Starts (or restarts, for a new segment) a recording to a fresh output target. */
    @SuppressLint("MissingPermission")
    fun startSegment(deviceName: String, onFileFinalized: (String, Boolean) -> Unit) {
        val vc = videoCapture ?: run {
            lastError = "videoCapture not ready"
            return
        }
        val target = StorageHelper.buildOutputOptions(context, deviceName)
        currentFileDescription = target.description

        val pending = vc.output.prepareRecording(context, target.options as androidx.camera.video.MediaStoreOutputOptions)
            .apply { if (hasAudioPermission()) withAudioEnabled() }

        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    segmentStartMs.set(System.currentTimeMillis())
                    isRecording = true
                    lastError = null
                }
                is VideoRecordEvent.Finalize -> {
                    val ok = !event.hasError()
                    if (!ok) {
                        lastError = "finalize error code=${event.error}: ${event.cause?.message}"
                        Log.e(TAG, "Recording finalize error: ${event.error}", event.cause)
                    }
                    onFileFinalized(target.description, ok)
                }
                is VideoRecordEvent.Status -> {
                    currentFileDurationSec.set(event.recordingStats.recordedDurationNanos / 1_000_000_000L)
                }
                else -> {}
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Stops the current segment. If `forNextSegment` the caller is expected to immediately call startSegment again. */
    fun stopSegment() {
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
        segmentStartMs.set(0L)
    }

    fun shutdown() {
        stopSegment()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}
