package com.samius.multicam

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple SharedPreferences-backed config. All fields have sane defaults so the
 * app is usable out of the box; can also be set via adb:
 *
 *   adb shell am start -n com.samius.multicam/.MainActivity \
 *     --es serverUrl "https://camera.samius.cz" \
 *     --es token "..." \
 *     --es deviceName "s20-a"
 */
object Config {
    private const val PREFS = "multicam_config"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = prefs.getString("serverUrl", "https://camera.samius.cz") ?: ""
        set(v) = prefs.edit().putString("serverUrl", v.trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "asdf") ?: "asdf"
        set(v) = prefs.edit().putString("token", v).apply()

    var deviceName: String
        get() = prefs.getString("deviceName", android.os.Build.MODEL ?: "android-cam") ?: ""
        set(v) = prefs.edit().putString("deviceName", v).apply()

    /** "WIDTHxHEIGHT", e.g. "1920x1080" or "3840x2160" */
    var resolution: String
        get() = prefs.getString("resolution", "1920x1080") ?: "1920x1080"
        set(v) = prefs.edit().putString("resolution", v).apply()

    var fps: Int
        get() = prefs.getInt("fps", 30)
        set(v) = prefs.edit().putInt("fps", v).apply()

    var bitrateKbps: Int
        get() = prefs.getInt("bitrateKbps", 18000)
        set(v) = prefs.edit().putInt("bitrateKbps", v).apply()

    var segmentMinutes: Int
        get() = prefs.getInt("segmentMinutes", 12)
        set(v) = prefs.edit().putInt("segmentMinutes", v).apply()

    /** 0 = auto exposure */
    var manualIso: Int
        get() = prefs.getInt("manualIso", 0)
        set(v) = prefs.edit().putInt("manualIso", v).apply()

    /** Exposure time in microseconds; 0 = auto */
    var manualExposureUs: Long
        get() = prefs.getLong("manualExposureUs", 0L)
        set(v) = prefs.edit().putLong("manualExposureUs", v).apply()

    /** White balance target in Kelvin; 0 = auto */
    var manualWbKelvin: Int
        get() = prefs.getInt("manualWbKelvin", 0)
        set(v) = prefs.edit().putInt("manualWbKelvin", v).apply()

    var useSdCard: Boolean
        get() = prefs.getBoolean("useSdCard", true)
        set(v) = prefs.edit().putBoolean("useSdCard", v).apply()

    /** SAF tree URI string, used as fallback when no removable MediaStore volume exists */
    var safTreeUri: String
        get() = prefs.getString("safTreeUri", "") ?: ""
        set(v) = prefs.edit().putString("safTreeUri", v).apply()

    fun resolutionWidth(): Int = resolution.substringBefore('x').toIntOrNull() ?: 1920
    fun resolutionHeight(): Int = resolution.substringAfter('x').toIntOrNull() ?: 1080

    fun applyFromIntentExtras(extras: android.os.Bundle?) {
        if (extras == null) return
        extras.getString("serverUrl")?.let { serverUrl = it }
        extras.getString("token")?.let { token = it }
        extras.getString("deviceName")?.let { deviceName = it }
        extras.getString("resolution")?.let { resolution = it }
        extras.getString("fps")?.toIntOrNull()?.let { fps = it }
        extras.getString("bitrateKbps")?.toIntOrNull()?.let { bitrateKbps = it }
        extras.getString("segmentMinutes")?.toIntOrNull()?.let { segmentMinutes = it }
    }
}
