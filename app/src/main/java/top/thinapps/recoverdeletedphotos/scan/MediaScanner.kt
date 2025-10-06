package top.thinapps.recoverdeletedphotos.scan

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.model.MediaItem

class MediaScanner(private val context: Context) {

    suspend fun scan(onProgress: (Int) -> Unit): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val cols = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED
        )
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(uri, cols, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val total = c.count.coerceAtLeast(1)
            var i = 0
            val idIdx = c.getColumnIndexOrThrow(cols[0])
            val nameIdx = c.getColumnIndexOrThrow(cols[1])
            val sizeIdx = c.getColumnIndexOrThrow(cols[2])
            val dateIdx = c.getColumnIndexOrThrow(cols[3])
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val contentUri = android.net.Uri.withAppendedPath(uri, id.toString())
                val name = c.getString(nameIdx)
                val size = c.getLong(sizeIdx)
                val date = c.getLong(dateIdx)
                items += MediaItem(id, contentUri, name, size, date)
                i++
                if (i % 20 == 0) onProgress(((i.toFloat() / total) * 100f).toInt().coerceIn(0, 100))
            }
        }
        onProgress(100)
        return@withContext items
    }
}
