package top.thinapps.recoverdeletedphotos.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.SimpleDateFormat
import java.util.*

// data class for recovered media, made parcelable for fragment argument passing
@Parcelize
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val origin: Origin = Origin.NORMAL
) : Parcelable {

    // defines if the file was normal or in the system trash folder
    enum class Origin { NORMAL, TRASHED }

    // computed property to return a user-friendly date string for display
    val dateReadable: String
        get() = sharedFormatter.format(Date(dateAddedSec * 1000))

    companion object {
        // singleton date formatter used to ensure consistent date output across locales
        private val sharedFormatter by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        }
    }
}
