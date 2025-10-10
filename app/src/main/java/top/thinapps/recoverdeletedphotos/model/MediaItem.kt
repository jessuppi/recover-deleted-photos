package top.thinapps.recoverdeletedphotos.model

import android.net.Uri
import android.os.Parcelable
import androidx.documentfile.provider.DocumentFile
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

    val dateReadable: String
        get() = DateFormat.getDateInstance().format(Date(dateAddedSec * 1000))

    companion object {
        // build from a SAF DocumentFile so hidden/.nomedia files merge cleanly
        fun fromDocumentFile(df: DocumentFile): MediaItem = MediaItem(
            id = df.uri.hashCode().toLong(),
            uri = df.uri,
            displayName = df.name ?: "unknown",
            sizeBytes = df.length(),
            dateAddedSec = df.lastModified() / 1000L
        )
    }
}
