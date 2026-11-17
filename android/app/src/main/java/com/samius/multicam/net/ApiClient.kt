package com.samius.multicam.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ServerState(val recording: Boolean, val cmdId: Int)

data class Heartbeat(
    val id: String,
    val name: String,
    val recording: Boolean,
    val currentFileDurationSec: Int,
    val batteryPct: Int?,
    val charging: Boolean?,
    val batteryTempC: Float?,
    val thermalStatus: String?,
    val freeSpaceBytes: Long?,
    val resolution: String?,
    val fps: Int?,
    val bitrateKbps: Int?,
    val currentFileName: String?,
)

/**
 * Thin client for the multicam server protocol (see handoff-multicam.md / server/server.js).
 * All calls are synchronous (blocking) - callers run them from a background coroutine/thread.
 * Any network failure throws; callers should catch and just skip that tick, since the
 * recording loop must never depend on network availability.
 */
class ApiClient(private var baseUrl: String, private var token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    fun update(baseUrl: String, token: String) {
        this.baseUrl = baseUrl.trimEnd('/')
        this.token = token
    }

    private fun withToken(path: String): String {
        val sep = if (path.contains('?')) "&" else "?"
        return "$baseUrl$path${sep}token=${token}"
    }

    fun getState(deviceId: String): ServerState {
        val url = withToken("/api/state?device=${deviceId}")
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("getState HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw RuntimeException("empty body")
            val json = JSONObject(body)
            return ServerState(json.getBoolean("recording"), json.getInt("cmdId"))
        }
    }

    fun postReport(hb: Heartbeat) {
        val json = JSONObject().apply {
            put("id", hb.id)
            put("name", hb.name)
            put("recording", hb.recording)
            put("currentFileDurationSec", hb.currentFileDurationSec)
            hb.batteryPct?.let { put("batteryPct", it) }
            hb.charging?.let { put("charging", it) }
            hb.batteryTempC?.let { put("batteryTempC", it) }
            hb.thermalStatus?.let { put("thermalStatus", it) }
            hb.freeSpaceBytes?.let { put("freeSpaceBytes", it) }
            hb.resolution?.let { put("resolution", it) }
            hb.fps?.let { put("fps", it) }
            hb.bitrateKbps?.let { put("bitrateKbps", it) }
            hb.currentFileName?.let { put("currentFileName", it) }
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(withToken("/api/report")).post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("postReport HTTP ${resp.code}")
        }
    }

    fun postPreview(deviceId: String, jpegBytes: ByteArray) {
        val body = jpegBytes.toRequestBody("image/jpeg".toMediaType())
        val req = Request.Builder().url(withToken("/api/preview/$deviceId")).post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("postPreview HTTP ${resp.code}")
        }
    }
}
