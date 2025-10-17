package top.thinapps.recoverdeletedphotos.ui

import androidx.lifecycle.ViewModel
import top.thinapps.recoverdeletedphotos.model.MediaItem

class ScanViewModel : ViewModel() {

    // holds scan results across fragments and survives config changes
    var results: List<MediaItem> = emptyList()
}
