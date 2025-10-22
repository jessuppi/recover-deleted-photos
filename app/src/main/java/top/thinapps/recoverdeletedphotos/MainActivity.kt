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

    // navigation app bar configuration for proper up button behavior
    private lateinit var appBarConfig: AppBarConfiguration

    // cached reference to the navigation controller
    private lateinit var navController: NavController

    // view binding for the activity layout, owned by the activity
    private lateinit var vb: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // keeps content below system bars for edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // inflate layout and set as content view
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // set the material toolbar as the action bar
        setSupportActionBar(vb.toolbar)

        // robust navigation controller lookup
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host) as? NavHostFragment
            ?: return // fails safe if layout id is missing

        navController = navHost.navController

        // wire navigation to action bar using nav graph labels for titles
        appBarConfig = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfig)

        // optional dynamic titles via nav arguments
        navController.addOnDestinationChangedListener { _, _, args ->
            val override = args?.getString("title_override")
            if (!override.isNullOrBlank()) {
                supportActionBar?.title = override
            }
        }
    }

    // supports the up button navigation functionality
    override fun onSupportNavigateUp(): Boolean {
        // checks if the controller has been initialized
        if (!::navController.isInitialized) {
            return super.onSupportNavigateUp()
        }
        // attempts to navigate up using the navigation component
        return navController.navigateUp(appBarConfig) || super.onSupportNavigateUp()
    }

    // centralized helper for fragments to set a custom toolbar title
    fun setToolbarTitle(title: CharSequence?) {
        supportActionBar?.title = title
    }

    // centralized helper for fragments to toggle toolbar visibility
    fun setToolbarVisible(visible: Boolean) {
        // safely checks if view binding is initialized before accessing the view
        if (::vb.isInitialized) vb.toolbar.isVisible = visible
    }
}
