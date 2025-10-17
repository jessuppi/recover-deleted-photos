package top.thinapps.recoverdeletedphotos

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.navigateUp
import top.thinapps.recoverdeletedphotos.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    // nav appbar config for proper up behavior
    private lateinit var appBarConfig: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // keep content below system bars
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // inflate layout and set as content view
        val vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // set toolbar as action bar
        setSupportActionBar(vb.toolbar)

        // wire navigation to action bar
        val navController = findNavController(R.id.nav_host)
        appBarConfig = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfig)
    }

    // support the up button
    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host)
        return navController.navigateUp(appBarConfig) || super.onSupportNavigateUp()
    }
}
