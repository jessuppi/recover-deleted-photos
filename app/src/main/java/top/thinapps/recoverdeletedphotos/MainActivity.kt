package top.thinapps.recoverdeletedphotos

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import top.thinapps.recoverdeletedphotos.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    // nav appbar config for proper up behavior
    private lateinit var appBarConfig: AppBarConfiguration

    // keep a reference so we don't refetch on every up press
    private lateinit var navController: NavController

    // single binding so the toolbar is owned/controlled here
    private lateinit var vb: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // keep content below system bars
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // inflate layout and set as content view
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // set toolbar as action bar
        setSupportActionBar(vb.toolbar)

        // robust navController lookup via NavHostFragment (null-safe)
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host) as? NavHostFragment
            ?: return // if layout id changes, fail safe without crashing

        navController = navHost.navController

        // wire navigation to action bar; titles come from nav_graph labels
        appBarConfig = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfig)
    }

    // support the up button
    override fun onSupportNavigateUp(): Boolean {
        // use the cached controller; fallback to super if not initialized
        if (!::navController.isInitialized) {
            return super.onSupportNavigateUp()
        }
        return navController.navigateUp(appBarConfig) || super.onSupportNavigateUp()
    }

    // centralized helpers so fragments don’t touch the toolbar directly

    // set a custom title when needed (otherwise nav_graph labels are used)
    fun setToolbarTitle(title: CharSequence?) {
        supportActionBar?.title = title
    }

    // toggle toolbar visibility per screen when desired
    fun setToolbarVisible(visible: Boolean) {
        if (::vb.isInitialized) vb.toolbar.isVisible = visible
    }
}
