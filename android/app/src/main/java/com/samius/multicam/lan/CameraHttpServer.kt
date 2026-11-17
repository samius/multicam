package com.samius.multicam.lan

import android.util.Log
import com.samius.multicam.Config
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Minimal embedded HTTP server run by a device in "camera" role, so a
 * "controller" phone on the same LAN/hotspot can control it directly -
 * no internet, no remote server required. Mirrors the same command
 * semantics as the remote server's REST API (see server/server.js) so the
 * two control paths (LAN direct vs. remote camera.samius.cz) stay compatible.
 *
 * Endpoints:
 *   GET  /status          -> JSON {id, name, recording, cmdId, batteryPct, ...}
 *   GET  /preview.jpg      -> last captured preview JPEG
 *   POST /command          -> body {"recording": bool}, bumps local cmdId
 *   GET  /state            -> JSON {recording, cmdId} (same shape as remote /api/state)
 */
class CameraHttpServer(
    private val statusProvider: () -> JSONObject,
    private val previewProvider: () -> ByteArray?,
    private val onCommand: (recording: Boolean) -> Unit,
) : NanoHTTPD(Config.LAN_PORT) {

    companion object {
        private const val TAG = "CameraHttpServer"
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/status" -> {
                    jsonResponse(statusProvider())
                }
                session.method == Method.GET && session.uri == "/state" -> {
                    val full = statusProvider()
                    val slim = JSONObject().apply {
                        put("recording", full.optBoolean("recording", false))
                        put("cmdId", full.optInt("cmdId", 0))
                    }
                    jsonResponse(slim)
                }
                session.method == Method.GET && session.uri == "/preview.jpg" -> {
                    val jpeg = previewProvider()
                    if (jpeg != null) {
                        newFixedLengthResponse(Response.Status.OK, "image/jpeg", jpeg.inputStream(), jpeg.size.toLong())
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no preview yet")
                    }
                }
                session.method == Method.POST && session.uri == "/command" -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val body = files["postData"] ?: "{}"
                    val json = JSONObject(body)
                    val recording = json.optBoolean("recording", false)
                    onCommand(recording)
                    jsonResponse(JSONObject().apply { put("ok", true) })
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve() failed: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
    }

    private fun jsonResponse(json: JSONObject): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }
}
