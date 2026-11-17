package com.samius.multicam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.samius.multicam.databinding.ActivityMainBinding

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var dimmed = false

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startServiceAndAttach()
        } else {
            Toast.makeText(this, "Bez oprávnění kamery/mikrofonu appka nemůže nahrávat", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.applyFromIntentExtras(intent.extras)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setMinimumBrightness()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recButton.setOnClickListener { toggleRecording() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.dimButton.setOnClickListener { toggleDim() }

        if (hasAllPermissions()) {
            startServiceAndAttach()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        startStatusRefresh()
    }

    /**
     * Keeps the screen on (FLAG_KEEP_SCREEN_ON, set separately) but dims the backlight to
     * its practical minimum so an unattended phone recording on stage doesn't spill light.
     * 0.0f can fully blank the panel on some OEM skins, so a tiny nonzero value is used.
     */
    private fun setMinimumBrightness() {
        val params = window.attributes
        params.screenBrightness = 0.01f
        window.attributes = params
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Config.applyFromIntentExtras(intent.extras)
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startServiceAndAttach() {
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        handler.postDelayed({ attachPreview() }, 300)
    }

    private fun attachPreview() {
        RecordingService.instance?.attachPreviewSurface(binding.previewView.surfaceProvider)
    }

    private fun toggleRecording() {
        // Local manual override: posts a command to ourselves by writing desired state directly
        // isn't exposed locally (state lives server-side), so this button just gives visual
        // feedback for local testing; the real control is remote via the server REST API.
        Toast.makeText(
            this,
            "Ovládání start/stop probíhá vzdáleně z webové stránky serveru. Toto tlačítko je jen pro lokální test preview.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun toggleDim() {
        dimmed = !dimmed
        binding.dimOverlay.visibility = if (dimmed) View.VISIBLE else View.GONE
        binding.dimOverlay.setOnClickListener { toggleDim() }
    }

    private fun startStatusRefresh() {
        handler.post(object : Runnable {
            override fun run() {
                val svc = RecordingService.instance
                if (svc != null) {
                    val rec = svc.isRecording()
                    binding.statusText.text = if (rec) {
                        "● REC ${svc.currentDurationSec()}s — ${Config.deviceName}"
                    } else {
                        "○ Stop — ${Config.deviceName}"
                    }
                    binding.recButton.isEnabled = true
                    binding.recButton.text = if (rec) "REC" else "STOP"
                    binding.recFrameOverlay.visibility = if (rec) View.VISIBLE else View.GONE
                    val err = svc.lastError()
                    binding.connText.text = if (err != null) {
                        "Server: ${Config.serverUrl}  |  chyba: $err"
                    } else {
                        "Server: ${Config.serverUrl}"
                    }
                } else {
                    binding.statusText.text = "Spouštím službu…"
                }
                handler.postDelayed(this, 1000)
            }
        })
    }
}
