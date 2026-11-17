package com.samius.multicam.lan

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Controller-role client: talks directly to a camera's embedded LAN HTTP
 * server (see CameraHttpServer), no remote server/internet involved.
 */
class LanCameraClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    fun fetchStatus(address: InetAddress, port: Int): JSONObject {
        val url = "http://${address.hostAddress}:$port/status"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("status HTTP ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    fun sendCommand(address: InetAddress, port: Int, recording: Boolean) {
        val url = "http://${address.hostAddress}:$port/command"
        val json = JSONObject().apply { put("recording", recording) }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("command HTTP ${resp.code}")
        }
    }

    fun previewUrl(address: InetAddress, port: Int): String =
        "http://${address.hostAddress}:$port/preview.jpg"

    fun fetchPreview(address: InetAddress, port: Int): ByteArray? {
        val url = previewUrl(address, port)
        val req = Request.Builder().url(url).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
