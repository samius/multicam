package com.samius.multicam

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.samius.multicam.databinding.ItemCameraBinding

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
            val staleNote = if (!cam.reachable || staleSec > 5) "  ⚠ výpadek ${staleSec}s" else ""
            val battText = cam.batteryPct?.let { "🔋 $it%" } ?: ""
            val thermText = cam.thermalStatus?.let { " 🌡 $it" } ?: ""
            binding.camStats.text = "$battText$thermText$staleNote"

            // Preview: show the last successfully fetched JPEG; fall back to a text
            // placeholder whenever we have none yet, or it's old enough to be stale
            // (camera unreachable / preview endpoint down / slow LAN), so the tile
            // never silently shows a frozen or broken image without saying so.
            val previewAgeSec = (System.currentTimeMillis() - cam.previewFetchedMs) / 1000
            val jpeg = cam.previewJpeg
            if (jpeg != null && previewAgeSec < 8) {
                val bmp = try {
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) {
                    binding.camPreview.setImageBitmap(bmp)
                    binding.camPreview.visibility = View.VISIBLE
                    binding.camPreviewFallback.visibility = View.GONE
                } else {
                    showPreviewFallback(cam)
                }
            } else {
                showPreviewFallback(cam)
            }

            binding.camStartButton.isEnabled = !cam.recording
            binding.camStopButton.isEnabled = cam.recording
            binding.camStartButton.setOnClickListener { onToggle(cam, true) }
            binding.camStopButton.setOnClickListener { onToggle(cam, false) }
        }

        private fun showPreviewFallback(cam: ControllerCameraState) {
            binding.camPreview.visibility = View.GONE
            binding.camPreviewFallback.visibility = View.VISIBLE
            binding.camPreviewFallback.text = when {
                !cam.reachable -> "nedostupná\n(${cam.address.hostAddress})"
                cam.previewJpeg == null -> "bez náhledu\n(čekám na první snímek)"
                else -> "náhled zastaralý"
            }
        }
    }
}
