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

    // removed context call to fix crash during size formatting
    val dateReadable: String
        get() = DateFormat.getDateInstance().format(Date(dateAddedSec * 1000))
}
