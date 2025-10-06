package top.thinapps.recoverdeletedphotos.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.DateFormat
import java.util.*

@Parcelize
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String?,
    val sizeBytes: Long,
    val dateAddedSec: Long
) : Parcelable {
    val sizeReadable: String get() = android.text.format.Formatter.formatShortFileSize(null, sizeBytes)
    val dateReadable: String get() = DateFormat.getDateInstance().format(Date(dateAddedSec * 1000))
}
