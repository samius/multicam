package com.samius.multicam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Autostarts the recording service after boot, so an S20 configured as a fixed camera
 * comes back up unattended after a reboot or battery pull. Requires the app to be exempted
 * from battery optimization and (per handoff doc) ideally set as the device's HOME launcher
 * so it also survives "no touch input available" scenarios.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Config.init(context)
            if (Config.isController()) return
            val serviceIntent = Intent(context, RecordingService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
