package top.thinapps.recoverdeletedphotos.ui

import androidx.lifecycle.ViewModel
import top.thinapps.recoverdeletedphotos.model.MediaItem

class ScanViewModel : ViewModel() {
    // holds scan results across fragments
    var items: List<MediaItem> = emptyList()
}
