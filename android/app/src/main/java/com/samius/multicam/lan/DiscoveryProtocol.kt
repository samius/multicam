package com.samius.multicam.lan

import android.util.Log
import com.samius.multicam.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Zero-config LAN discovery, no internet/server required.
 *
 * Protocol: simple UDP request/response on [Config.DISCOVERY_PORT]:
 *   Controller -> broadcast "{MAGIC}:QUERY" to 255.255.255.255:{port}
 *   Camera     -> unicast reply "{MAGIC}:{json}" straight back to the sender's
 *                 (address, port) pair, where json = {id, name, lanPort, recording}
 *
 * The camera's IP is never sent in the payload - it's taken from the UDP
 * packet's source address, which is always correct regardless of the
 * camera's own idea of its IP (multiple interfaces, VPNs, etc).
 */
object DiscoveryProtocol {
    private const val TAG = "Discovery"
    private val QUERY = "${Config.DISCOVERY_MAGIC}:QUERY"

    data class DiscoveredCamera(
        val id: String,
        val name: String,
        val address: InetAddress,
        val lanPort: Int,
        val recording: Boolean,
    )

    // ---- Camera side: responds to broadcast queries ----

    class Responder(private val statusProvider: () -> JSONObject) {
        @Volatile private var running = false
        private var socket: DatagramSocket? = null
        private var job: Job? = null
        private val scope = CoroutineScope(Dispatchers.IO + Job())

        fun start() {
            if (running) return
            running = true
            job = scope.launch {
                try {
                    val s = DatagramSocket(null)
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(Config.DISCOVERY_PORT))
                    socket = s
                    val buf = ByteArray(512)
                    while (running) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            s.receive(packet)
                        } catch (e: Exception) {
                            if (running) Log.w(TAG, "receive failed: ${e.message}")
                            continue
                        }
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (text == QUERY) {
                            respond(s, packet.address, packet.port)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Responder failed to bind: ${e.message}")
                }
            }
        }

        private fun respond(socket: DatagramSocket, address: InetAddress, port: Int) {
            try {
                val json = statusProvider()
                val payload = "${Config.DISCOVERY_MAGIC}:$json".toByteArray(Charsets.UTF_8)
                val reply = DatagramPacket(payload, payload.size, address, port)
                socket.send(reply)
            } catch (e: Exception) {
                Log.w(TAG, "reply failed: ${e.message}")
            }
        }

        fun stop() {
            running = false
            socket?.close()
            job?.cancel()
        }
    }

    // ---- Controller side: broadcasts a query, collects replies ----

    /** Blocking call (run from IO dispatcher): sends the broadcast and gathers replies for [windowMs].
     * Optionally pass [context] to additionally compute the subnet-directed broadcast address via
     * WifiManager - some APs with client isolation / odd DHCP setups don't forward 255.255.255.255
     * reliably, so trying both increases the odds of finding every camera. */
    fun discoverOnce(windowMs: Long = 2000, context: android.content.Context? = null): List<DiscoveredCamera> {
        val results = mutableListOf<DiscoveredCamera>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 250
            socket.bind(InetSocketAddress(0))

            val queryBytes = QUERY.toByteArray(Charsets.UTF_8)
            val broadcastAddrs = mutableListOf(InetAddress.getByName("255.255.255.255"))
            subnetBroadcastAddress(context)?.let { broadcastAddrs.add(it) }
            val queryPackets = broadcastAddrs.map { DatagramPacket(queryBytes, queryBytes.size, it, Config.DISCOVERY_PORT) }

            val deadline = System.currentTimeMillis() + windowMs
            queryPackets.forEach { try { socket.send(it) } catch (_: Exception) {} }
            var lastResend = System.currentTimeMillis()

            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < deadline) {
                // Resend every ~700ms in case the first broadcast gets dropped.
                if (System.currentTimeMillis() - lastResend > 700) {
                    queryPackets.forEach { try { socket.send(it) } catch (_: Exception) {} }
                    lastResend = System.currentTimeMillis()
                }
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val prefix = "${Config.DISCOVERY_MAGIC}:"
                if (!text.startsWith(prefix)) continue
                try {
                    val json = JSONObject(text.removePrefix(prefix))
                    val cam = DiscoveredCamera(
                        id = json.getString("id"),
                        name = json.optString("name", json.getString("id")),
                        address = packet.address,
                        lanPort = json.optInt("lanPort", Config.LAN_PORT),
                        recording = json.optBoolean("recording", false),
                    )
                    val idx = results.indexOfFirst { it.id == cam.id }
                    if (idx >= 0) {
                        results[idx] = cam
                    } else {
                        results.add(cam)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bad discovery reply: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "discoverOnce failed: ${e.message}")
        } finally {
            socket?.close()
        }
        return results
    }

    private fun subnetBroadcastAddress(context: android.content.Context?): InetAddress? {
        if (context == null) return null
        return try {
            val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val dhcp = wifiManager.dhcpInfo ?: return null
            val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
            val quads = ByteArray(4)
            for (k in 0..3) {
                quads[k] = ((broadcast shr (k * 8)) and 0xFF).toByte()
            }
            InetAddress.getByAddress(quads)
        } catch (e: Exception) {
            Log.w(TAG, "subnetBroadcastAddress failed: ${e.message}")
            null
        }
    }
}
