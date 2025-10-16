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
import java.util.Locale

object Recovery {

    // copies all supported items; returns number of successes
    suspend fun copyAll(context: Context, items: List<MediaItem>): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext 0

        val resolver = context.contentResolver
        var ok = 0
        for (item in items.distinctBy { it.id }) {
            kotlinx.coroutines.ensureActive()
            if (copyOne(resolver, item)) ok++
        }
        ok
    }

    // single item copy using mediastore insert + stream copy
    private fun copyOne(resolver: ContentResolver, item: MediaItem): Boolean {
        // normalize filename
        val name = (item.displayName ?: "").ifBlank { "recovered_${System.currentTimeMillis()}" }

        // resolve or guess mime
        val mime = resolver.getType(item.uri) ?: guessMime(name)

        // pick collection and subfolder; skip unsupported types
        val target = targetForMime(mime) ?: return false
        val (collection, relativePath) = target

        // prepare destination row
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }

        val dest: Uri = resolver.insert(collection, values) ?: return false

        return try {
            resolver.openInputStream(item.uri).use { input ->
                resolver.openOutputStream(dest, "w").use { output ->
                    if (input == null || output == null) throw IOException("stream error")
                    input.copyTo(output)
                }
            }
            true
        } catch (_: Exception) {
            runCatching { resolver.delete(dest, null, null) }
            false
        }
    }

    // maps mime to allowed collections only
    private fun targetForMime(mime: String): Pair<Uri, String>? {
        return when {
            mime.startsWith("image/") ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Recovered"
            mime.startsWith("video/") ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Pictures/Recovered"
            mime.startsWith("audio/") ->
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Recovered"
            else -> null
        }
    }

    // simple mime guess from filename
    private fun guessMime(name: String): String {
        val n = name.lowercase(Locale.getDefault())
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
