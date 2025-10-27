package top.thinapps.recoverdeletedphotos.ui

import android.app.Activity
import android.view.View
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.R

object SnackbarUtils {
    private var inProgressBar: Snackbar? = null

    private fun rootView(activity: Activity): View =
        activity.findViewById(android.R.id.content)

    private fun anchorView(activity: Activity): View? =
        activity.findViewById(R.id.recoverButton)

    fun showRecovering(activity: Activity, totalPlanned: Int? = null) {
        dismissRecovering()
        val msg = if (totalPlanned != null && totalPlanned > 0) {
            "Recovering $totalPlanned items…"
        } else {
            "Recovering items…"
        }
        inProgressBar = Snackbar.make(rootView(activity), msg, Snackbar.LENGTH_INDEFINITE).apply {
            setAnchorView(anchorView(activity))
            show()
        }
    }

    fun dismissRecovering() {
        inProgressBar?.dismiss()
        inProgressBar = null
    }

    suspend fun showRecovered(activity: Activity, count: Int, isAudioOnly: Boolean) {
        withContext(Dispatchers.Main) {
            val dest = if (isAudioOnly) "Music/Recovered" else "Pictures/Recovered"
            val label = if (count == 1) "item" else "items"
            Snackbar.make(
                rootView(activity),
                "$count $label recovered to $dest",
                Snackbar.LENGTH_LONG
            ).setAnchorView(anchorView(activity))
             .show()
        }
    }
}
