package top.thinapps.recoverdeletedphotos.scan

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.model.MediaItem

class MediaScanner(private val context: Context) {

    // emits real found and total so the UI can show "Found X files" and true percent
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
                    val id = c.getLong(idIdx)
                    val contentUri = Uri.withAppendedPath(uri, id.toString())
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
                    onProgress(found, total) // per-row; UI throttles display rate
                }

                // final emission (in case total==found but the UI tick is catching up)
                onProgress(found, total)
            }

            out
        }
}
