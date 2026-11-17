package com.samius.multicam

import java.net.InetAddress

/** Mutable camera row shown in the controller's RecyclerView, refreshed on each poll/scan. */
data class ControllerCameraState(
    val id: String,
    var name: String,
    var address: InetAddress,
    var lanPort: Int,
    var recording: Boolean = false,
    var batteryPct: Int? = null,
    var thermalStatus: String? = null,
    var lastSeenMs: Long = System.currentTimeMillis(),
    var reachable: Boolean = true,
)
