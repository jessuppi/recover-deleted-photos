package top.thinapps.recoverdeletedphotos.scan

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import top.thinapps.recoverdeletedphotos.model.MediaItem

class MediaScanner(private val context: Context) {

    /**
     * MediaStore-only scan.
     * - API 30+: includes trashed rows (QUERY_ARG_MATCH_TRASHED = MATCH_INCLUDE), excludes pending.
     * - < API 30: no trash concept; returns finalized media only.
     */
    suspend fun scan(
        includeImages: Boolean,
        includeVideos: Boolean,
        includeAudio: Boolean,
        onProgress: (found: Int, total: Int) -> Unit
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MediaItem>()

        // Which tables to query
        val queries = buildList {
            if (includeImages) add(
                QuerySpec(
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id = MediaStore.Images.Media._ID,
                    name = MediaStore.Images.Media.DISPLAY_NAME,
                    size = MediaStore.Images.Media.SIZE,
                    date = MediaStore.Images.Media.DATE_ADDED
                )
            )
            if (includeVideos) add(
                QuerySpec(
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id = MediaStore.Video.Media._ID,
                    name = MediaStore.Video.Media.DISPLAY_NAME,
                    size = MediaStore.Video.Media.SIZE,
                    date = MediaStore.Video.Media.DATE_ADDED
                )
            )
            if (includeAudio) add(
                QuerySpec(
                    uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id = MediaStore.Audio.Media._ID,
                    name = MediaStore.Audio.Media.DISPLAY_NAME,
                    size = MediaStore.Audio.Media.SIZE,
                    date = MediaStore.Audio.Media.DATE_ADDED
                )
            )
        }
        if (queries.isEmpty()) return@withContext emptyList<MediaItem>()

        // Total for progress: use the same extras as the main query (so trash inclusion matches)
        val total = queries.sumOf { q ->
            context.contentResolver.query(
                q.uri,
                arrayOf(q.id),
                buildQueryExtras(sortCol = q.date),
                null
            )?.use { it.count } ?: 0
        }.coerceAtLeast(1)

        var found = 0
        for (q in queries) {
            // Add IS_TRASHED only where available (API 30+)
            val projection = if (Build.VERSION.SDK_INT >= 30)
                arrayOf(q.id, q.name, q.size, q.date, MediaStore.MediaColumns.IS_TRASHED)
            else
                arrayOf(q.id, q.name, q.size, q.date)

            context.contentResolver.query(
                q.uri,
                projection,
                buildQueryExtras(sortCol = q.date),
                null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(q.id)
                val nameIdx = c.getColumnIndexOrThrow(q.name)
                val sizeIdx = c.getColumnIndexOrThrow(q.size)
                val dateIdx = c.getColumnIndexOrThrow(q.date)
                val trashedIdx = if (Build.VERSION.SDK_INT >= 30)
                    c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED) else -1

                while (c.moveToNext()) {
                    if (!coroutineContext.isActive) break

                    val id = c.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(q.uri, id)
                    val name = c.getString(nameIdx)
                    val size = c.getLong(sizeIdx)
                    val dateAdded = c.getLong(dateIdx)
                    val isTrashed = trashedIdx != -1 && c.getInt(trashedIdx) == 1

                    out += MediaItem(
                        id = id,
                        uri = uri,
                        displayName = name,
                        sizeBytes = size,
                        dateAddedSec = dateAdded,
                        origin = if (isTrashed) MediaItem.Origin.TRASHED else MediaItem.Origin.NORMAL
                    )

                    found++
                    onProgress(found, total)

                    // yield periodically for responsiveness
                    if ((found and 63) == 0) {
                        yield()
                        if (!coroutineContext.isActive) break
                    }
                }
            }
        }

        onProgress(found, total)
        out
    }

    /**
     * Build query extras:
     * - Sort by provided column (DESC)
     * - API 30+: include trashed rows; do not match pending (keeps pending excluded)
     * - < API 30: return null (regular selection; pending not exposed on old APIs)
     */
    private fun buildQueryExtras(sortCol: String): Bundle? {
        if (Build.VERSION.SDK_INT < 30) return null
        return Bundle().apply {
            putStringArray(
                android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(sortCol)
            )
            putInt(
                android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            // NOTE: We intentionally DO NOT set QUERY_ARG_MATCH_PENDING, which keeps pending excluded.
        }
    }

    private data class QuerySpec(
        val uri: Uri,
        val id: String,
        val name: String,
        val size: String,
        val date: String
    )
}
