package top.thinapps.recoverdeletedphotos.recover

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import top.thinapps.recoverdeletedphotos.model.MediaItem
import java.io.IOException
import java.util.Locale
import java.util.UUID

object Recovery {

    // copies all supported items and returns the number of successes
    suspend fun copyAll(context: Context, items: List<MediaItem>): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext 0

        val resolver = context.contentResolver
        var ok = 0
        for (item in items.distinctBy { it.uri }) {
            if (!coroutineContext.isActive) return@withContext ok
            if (item.sizeBytes == 0L) continue
            if (copyOne(resolver, item)) ok++
        }
        ok
    }

    // single item copy using mediastore insert and stream copy
    private fun copyOne(resolver: ContentResolver, item: MediaItem): Boolean {
        val name = item.displayName.ifBlank { "recovered_${UUID.randomUUID()}" }
        val mime = resolver.getType(item.uri) ?: guessMime(name)

        val target = targetForMime(mime) ?: return false
        val (collection, relativePath) = target

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.IS_PENDING, 1) // keep hidden until fully written
                if (mime.startsWith("image/") || mime.startsWith("video/")) {
                    put(MediaStore.MediaColumns.DATE_TAKEN, item.dateAddedSec * 1000) // preserve taken time
                }
            }
        }

        val dest: Uri = resolver.insert(collection, values) ?: return false

        return try {
            resolver.openInputStream(item.uri)?.use { input ->
                resolver.openOutputStream(dest, "w")?.use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("null stream")

            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0) // make visible after success
                }
                resolver.update(dest, done, null, null)
            }
            true
        } catch (_: Exception) {
            runCatching { resolver.delete(dest, null, null) }
            false
        }
    }

    // maps mime to allowed collections only
    private fun targetForMime(mime: String): Pair<Uri, String>? {
        val m = mime.lowercase(Locale.ROOT)
        return when {
            m.startsWith("image/") ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Recovered"
            m.startsWith("video/") ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Pictures/Recovered"
            m.startsWith("audio/") ->
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Recovered"
            else -> null
        }
    }

    // simple mime guess from filename
    private fun guessMime(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".gif") -> "image/gif"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".heic") || n.endsWith(".heif") -> "image/heif"
            n.endsWith(".bmp") -> "image/bmp"
            n.endsWith(".tiff") || n.endsWith(".tif") -> "image/tiff"
            n.endsWith(".avif") -> "image/avif"
            n.endsWith(".mp4") -> "video/mp4"
            n.endsWith(".mov") -> "video/quicktime"
            n.endsWith(".3gp") -> "video/3gpp"
            n.endsWith(".mkv") -> "video/x-matroska"
            n.endsWith(".avi") -> "video/x-msvideo"
            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".m4a") -> "audio/mp4"
            n.endsWith(".aac") -> "audio/aac"
            n.endsWith(".ogg") -> "audio/ogg"
            n.endsWith(".opus") -> "audio/opus"
            n.endsWith(".flac") -> "audio/flac"
            n.endsWith(".wav") -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
