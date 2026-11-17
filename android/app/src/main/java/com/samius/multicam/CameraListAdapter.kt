package com.samius.multicam

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.samius.multicam.databinding.ItemCameraBinding
import com.samius.multicam.lan.LanCameraClient

class CameraListAdapter(
    private val onToggle: (ControllerCameraState, Boolean) -> Unit,
) : RecyclerView.Adapter<CameraListAdapter.ViewHolder>() {

    private val items = mutableListOf<ControllerCameraState>()

    fun submit(newItems: List<ControllerCameraState>) {
        items.clear()
        items.addAll(newItems.sortedBy { it.id })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCameraBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemCameraBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cam: ControllerCameraState) {
            binding.camName.text = cam.name
            binding.camAddress.text = cam.address.hostAddress
            binding.recDot.setBackgroundResource(if (cam.recording) R.drawable.dot_red else R.drawable.dot_gray)

            val staleSec = (System.currentTimeMillis() - cam.lastSeenMs) / 1000
            val staleNote = if (staleSec > 5) "  ⚠ výpadek ${staleSec}s" else ""
            val battText = cam.batteryPct?.let { "🔋 $it%" } ?: ""
            val thermText = cam.thermalStatus?.let { " 🌡 $it" } ?: ""
            binding.camStats.text = "$battText$thermText$staleNote"

            binding.camStartButton.isEnabled = !cam.recording
            binding.camStopButton.isEnabled = cam.recording
            binding.camStartButton.setOnClickListener { onToggle(cam, true) }
            binding.camStopButton.setOnClickListener { onToggle(cam, false) }
        }
    }
}
