package com.samius.multicam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.samius.multicam.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Config.safTreeUri = uri.toString()
            Toast.makeText(this, "Složka uložena: $uri", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.editServerUrl.setText(Config.serverUrl)
        binding.editToken.setText(Config.token)
        binding.editDeviceName.setText(Config.deviceName)
        binding.editResolution.setText(Config.resolution)
        binding.editFps.setText(Config.fps.toString())
        binding.editBitrate.setText(Config.bitrateKbps.toString())
        binding.editSegmentMinutes.setText(Config.segmentMinutes.toString())
        binding.editIso.setText(Config.manualIso.toString())
        binding.editExposureUs.setText(Config.manualExposureUs.toString())
        binding.editWbKelvin.setText(Config.manualWbKelvin.toString())
        binding.checkUseSdCard.isChecked = Config.useSdCard

        binding.saveButton.setOnClickListener { save() }
        binding.pickFolderButton.setOnClickListener { folderPicker.launch(null) }
    }

    private fun save() {
        Config.serverUrl = binding.editServerUrl.text.toString().trim()
        Config.token = binding.editToken.text.toString().trim()
        Config.deviceName = binding.editDeviceName.text.toString().trim()
        Config.resolution = binding.editResolution.text.toString().trim()
        Config.fps = binding.editFps.text.toString().toIntOrNull() ?: Config.fps
        Config.bitrateKbps = binding.editBitrate.text.toString().toIntOrNull() ?: Config.bitrateKbps
        Config.segmentMinutes = binding.editSegmentMinutes.text.toString().toIntOrNull() ?: Config.segmentMinutes
        Config.manualIso = binding.editIso.text.toString().toIntOrNull() ?: 0
        Config.manualExposureUs = binding.editExposureUs.text.toString().toLongOrNull() ?: 0L
        Config.manualWbKelvin = binding.editWbKelvin.text.toString().toIntOrNull() ?: 0
        Config.useSdCard = binding.checkUseSdCard.isChecked

        Toast.makeText(this, "Uloženo. Restartuji nahrávací službu…", Toast.LENGTH_SHORT).show()
        stopService(Intent(this, RecordingService::class.java))
        finish()
    }
}
