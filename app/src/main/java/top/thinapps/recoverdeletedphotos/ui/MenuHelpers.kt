package top.thinapps.recoverdeletedphotos.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle

// helper to centralize fragment menu handling
fun Fragment.withMenu(
    menuRes: Int,
    onCreate: (Menu) -> Unit = {},
    onSelect: (MenuItem) -> Boolean
) {
    val host: MenuHost = requireActivity()
    host.addMenuProvider(object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            menu.clear()
            inflater.inflate(menuRes, menu)
            onCreate(menu)
        }
        override fun onMenuItemSelected(item: MenuItem): Boolean {
            return onSelect(item)
        }
    }, viewLifecycleOwner, Lifecycle.State.RESUMED)
}
