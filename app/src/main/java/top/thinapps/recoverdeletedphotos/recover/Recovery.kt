package top.thinapps.recoverdeletedphotos.recover

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.model.MediaItem
import java.io.IOException

object Recovery {

    /** Copies all given items into public collections:
     *  - Images & Videos → Pictures/Recovered
     *  - Audio → Music/Recovered
     *  Returns number of successfully copied items.
     *  (No untrash/restore prompts; simple, unified destinations.)
     */
    suspend fun copyAll(context: Context, items: List<MediaItem>): Int = withContext(Dispatchers.IO) {
        val r = context.contentResolver
        var ok = 0
        for (item in items) {
            if (copyOne(r, item)) ok++
        }
        ok
    }

    private fun copyOne(resolver: ContentResolver, item: MediaItem): Boolean {
        val mime = resolver.getType(item.uri) ?: guessMime(item.displayName)
        val (collection, relativePath) = targetForMime(mime)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName ?: "recovered_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            // Put into scoped, public folder without SAF
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
        }

        val dest: Uri = resolver.insert(collection, values) ?: return false

        return try {
            resolver.openInputStream(item.uri).use { input ->
                resolver.openOutputStream(dest, "w").use { output ->
                    if (input == null || output == null) throw IOException("Stream error")
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            // cleanup on failure
            runCatching { resolver.delete(dest, null, null) }
            false
        }
    }

    private fun targetForMime(mime: String): Pair<Uri, String> {
        return when {
            mime.startsWith("image/") ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Recovered"
            mime.startsWith("video/") ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Pictures/Recovered"
            mime.startsWith("audio/") ->
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Recovered"
            else ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/Recovered"
        }
    }

    private fun guessMime(name: String?): String {
        val n = name?.lowercase().orEmpty()
        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".gif") -> "image/gif"
            n.endsWith(".mp4") -> "video/mp4"
            n.endsWith(".mov") -> "video/quicktime"
            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".wav") -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
