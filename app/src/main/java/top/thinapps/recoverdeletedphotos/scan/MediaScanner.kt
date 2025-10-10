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

    // scans selected media types; includes trashed on api 30+; excludes pending always
    suspend fun scan(
        includeImages: Boolean,
        includeVideos: Boolean,
        includeAudio: Boolean,
        onProgress: (found: Int, total: Int) -> Unit
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MediaItem>()

        // build which tables to query
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

        // compute total across selected sources using the same trash-inclusive query path
        val total = queries.sumOf { q ->
            context.contentResolver.query(
                q.uri,
                arrayOf(q.id),
                trashExtras(sortCol = q.date),
                null
            )?.use { it.count } ?: 0
        }.coerceAtLeast(1)

        var found = 0
        for (q in queries) {
            val projection = arrayOf(q.id, q.name, q.size, q.date)

            context.contentResolver.query(
                q.uri,
                projection,
                trashExtras(sortCol = q.date),
                null
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

                    // yield periodically so cancel is snappy and ui stays smooth
                    if ((found and 63) == 0) {
                        yield()
                        if (!coroutineContext.isActive) break
                    }
                }
            }
        }

        // final emission for any trailing ui catch-up
        onProgress(found, total)

        out
    }

    // builds bundle extras that include trashed rows on api 30+ and keep pending excluded
    private fun trashExtras(sortCol: String): Bundle? {
        return if (Build.VERSION.SDK_INT >= 30) {
            Bundle().apply {
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(sortCol)
                )
                putInt(
                    android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                )
                putInt(
                    MediaStore.QUERY_ARG_MATCH_TRASHED,
                    MediaStore.MATCH_INCLUDE
                )
                // do not set QUERY_ARG_MATCH_PENDING so pending stays excluded
            }
        } else {
            null
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
