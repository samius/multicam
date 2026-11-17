package com.samius.multicam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Picks an output target for the next recording segment:
 *   1. If an SD card (removable MediaStore volume) exists and useSdCard is on, use it via MediaStore.
 *   2. Else if a SAF tree URI was granted (manual folder pick on a card the OS doesn't expose as
 *      a MediaStore volume), fall back to a plain File inside that tree via DocumentFile... but
 *      CameraX Recorder needs a FileDescriptor/File, so we resolve the SAF tree to a real path
 *      when possible, otherwise fall back further to internal storage.
 *   3. Else internal (app-specific or shared) storage, with a warning surfaced to the UI/log.
 */
object StorageHelper {

    data class Target(val options: Any, val description: String, val isSdCard: Boolean)

    fun nextFileBaseName(deviceName: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        return "multicam_${deviceName}_$ts"
    }

    /** Lists non-primary (removable) MediaStore volumes, i.e. SD cards, on API 29+. */
    private fun removableVolumeNames(context: Context): List<String> {
        return try {
            MediaStore.getExternalVolumeNames(context)
                .filter { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun hasSdCardVolume(context: Context): Boolean = removableVolumeNames(context).isNotEmpty()

    /**
     * Builds a MediaStoreOutputOptions targeting the removable volume if present and desired,
     * otherwise the primary (internal) volume. Returns the options plus a human description.
     */
    fun buildOutputOptions(context: Context, deviceName: String): Target {
        val baseName = nextFileBaseName(deviceName)
        val volumes = removableVolumeNames(context)
        val useRemovable = Config.useSdCard && volumes.isNotEmpty()
        val volumeName = if (useRemovable) volumes.first() else MediaStore.VOLUME_EXTERNAL_PRIMARY

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Multicam")
        }

        val collectionUri = MediaStore.Video.Media.getContentUri(volumeName)
        val options = MediaStoreOutputOptions.Builder(context.contentResolver, collectionUri)
            .setContentValues(contentValues)
            .build()

        val desc = if (useRemovable) "SD karta ($volumeName)" else "interní úložiště (SD karta nenalezena!)"
        return Target(options, desc, useRemovable)
    }

    /** Free space on the target volume, best-effort. Falls back to internal storage stat. */
    fun freeSpaceBytes(context: Context): Long {
        return try {
            val volumes = removableVolumeNames(context)
            val path = if (Config.useSdCard && volumes.isNotEmpty()) {
                // Best-effort: on most devices the removable volume still resolves via
                // getExternalFilesDirs() ordering; fall back to primary if unavailable.
                context.getExternalFilesDirs(null).getOrNull(1) ?: Environment.getDataDirectory()
            } else {
                Environment.getDataDirectory()
            }
            val stat = StatFs(path.path)
            stat.availableBytes
        } catch (e: Exception) {
            -1L
        }
    }
}
