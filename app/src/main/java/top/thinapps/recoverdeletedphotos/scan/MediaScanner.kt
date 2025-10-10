package top.thinapps.recoverdeletedphotos.scan

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import top.thinapps.recoverdeletedphotos.model.MediaItem

class MediaScanner(private val context: Context) {

    /**
     * Scans selected media types. Emits (found, total) so UI can show true progress.
     *
     * @param includeImages scan MediaStore.Images when true
     * @param includeVideos scan MediaStore.Video when true
     * @param includeAudio  scan MediaStore.Audio when true
     */
    suspend fun scan(
        includeImages: Boolean,
        includeVideos: Boolean,
        includeAudio: Boolean,
        onProgress: (found: Int, total: Int) -> Unit
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MediaItem>()

        // Build which tables to query based on selection
        val queries = mutableListOf<QuerySpec>()
        if (includeImages) {
            queries += QuerySpec(
                uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id = MediaStore.Images.Media._ID,
                name = MediaStore.Images.Media.DISPLAY_NAME,
                size = MediaStore.Images.Media.SIZE,
                date = MediaStore.Images.Media.DATE_ADDED
            )
        }
        if (includeVideos) {
            queries += QuerySpec(
                uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                id = MediaStore.Video.Media._ID,
                name = MediaStore.Video.Media.DISPLAY_NAME,
                size = MediaStore.Video.Media.SIZE,
                date = MediaStore.Video.Media.DATE_ADDED
            )
        }
        if (includeAudio) {
            queries += QuerySpec(
                uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                id = MediaStore.Audio.Media._ID,
                name = MediaStore.Audio.Media.DISPLAY_NAME,
                size = MediaStore.Audio.Media.SIZE,
                date = MediaStore.Audio.Media.DATE_ADDED
            )
        }

        if (queries.isEmpty()) return@withContext emptyList<MediaItem>()

        // Compute total across all selected sources; coerce >= 1 so UI math is safe
        val total = queries.sumOf { q ->
            context.contentResolver.query(q.uri, arrayOf(q.id), null, null, null)
                ?.use { it.count } ?: 0
        }.coerceAtLeast(1)

        var found = 0
        for (q in queries) {
            val projection = arrayOf(q.id, q.name, q.size, q.date)
            context.contentResolver.query(
                q.uri,
                projection,
                null,
                null,
                "${q.date} DESC"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(q.id)
                val nameIdx = c.getColumnIndexOrThrow(q.name)
                val sizeIdx = c.getColumnIndexOrThrow(q.size)
                val dateIdx = c.getColumnIndexOrThrow(q.date)

                while (c.moveToNext()) {
                    if (!coroutineContext.isActive) break

                    val id = c.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(q.uri, id)
                    val name = c.getString(nameIdx)
                    val size = c.getLong(sizeIdx)
                    val dateAdded = c.getLong(dateIdx)

                    out += MediaItem(
                        id = id,
                        uri = uri,
                        displayName = name,
                        sizeBytes = size,
                        dateAddedSec = dateAdded
                    )

                    found++
                    onProgress(found, total)

                    // yield periodically so cancel is snappy and UI stays smooth
                    if ((found and 63) == 0) {
                        yield()
                        if (!coroutineContext.isActive) break
                    }
                }
            }
        }

        // final emission for any trailing UI catch-up
        onProgress(found, total)

        out
    }

    private data class QuerySpec(
        val uri: Uri,
        val id: String,
        val name: String,
        val size: String,
        val date: String
    )
}
