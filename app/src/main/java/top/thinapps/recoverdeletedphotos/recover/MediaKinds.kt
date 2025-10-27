package top.thinapps.recoverdeletedphotos.media

data class MediaItem(val mimeType: String? = null, val path: String? = null)

object MediaKinds {
    fun isAudioOnly(items: List<MediaItem>): Boolean {
        if (items.isEmpty()) return false
        var sawAudio = false
        for (i in items) {
            val mt = i.mimeType ?: guessMimeFromPath(i.path)
            when {
                mt?.startsWith("audio/") == true -> sawAudio = true
                mt?.startsWith("image/") == true -> return false
                mt?.startsWith("video/") == true -> return false
            }
        }
        return sawAudio
    }

    private fun guessMimeFromPath(path: String?): String? {
        if (path == null) return null
        val lower = path.lowercase()
        return when {
            lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".wav") || lower.endsWith(".flac") -> "audio/*"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".heic") -> "image/*"
            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") || lower.endsWith(".webm") -> "video/*"
            else -> null
        }
    }
}
