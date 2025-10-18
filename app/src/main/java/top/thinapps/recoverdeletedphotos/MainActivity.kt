package top.thinapps.recoverdeletedphotos

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // keep content below system bars
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // inflate layout and set as content view
        val vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // set toolbar as action bar
        setSupportActionBar(vb.toolbar)

        // robust navController lookup via NavHostFragment (null-safe)
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host) as? NavHostFragment
            ?: return // if layout id changes, fail safe without crashing

        navController = navHost.navController

        // wire navigation to action bar
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
}
