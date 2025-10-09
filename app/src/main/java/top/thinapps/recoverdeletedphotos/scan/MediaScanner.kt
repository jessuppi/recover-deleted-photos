package top.thinapps.recoverdeletedphotos.scan

import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import top.thinapps.recoverdeletedphotos.model.MediaItem

class MediaScanner(private val context: Context) {

    // emits real found and total so the ui can show "found x files" and true percent
    suspend fun scan(onProgress: (found: Int, total: Int) -> Unit): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<MediaItem>()

            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED
            )

            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val total = c.count.coerceAtLeast(1)

                val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                var found = 0
                while (c.moveToNext()) {
                    if (!coroutineContext.isActive) break

                    val id = c.getLong(idIdx)
                    val contentUri = ContentUris.withAppendedId(uri, id)
                    val name = c.getString(nameIdx)
                    val size = c.getLong(sizeIdx)
                    val dateAdded = c.getLong(dateIdx)

                    out += MediaItem(
                        id = id,
                        uri = contentUri,
                        displayName = name,
                        sizeBytes = size,
                        dateAddedSec = dateAdded
                    )

                    found++
                    onProgress(found, total)

                    if (found % 64 == 0) {
                        yield()
                        if (!coroutineContext.isActive) break
                    }
                }

                onProgress(found, total)
            }

            out
        }
}
