package top.thinapps.recoverdeletedphotos.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.SimpleDateFormat
import java.util.*

@Parcelize
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val origin: Origin = Origin.NORMAL
) : Parcelable {

    // origin type for scanned files
    enum class Origin { NORMAL, TRASHED }

    // readable date for ui display
    val dateReadable: String
        get() = sharedFormatter.format(Date(dateAddedSec * 1000))

    companion object {
        // shared date formatter (consistent across locales)
        private val sharedFormatter by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        }
    }
}
