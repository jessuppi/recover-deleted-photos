package top.thinapps.recoverdeletedphotos

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import top.thinapps.recoverdeletedphotos.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    // view binding reference for main layout
    private lateinit var vb: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // keep content below system bars (notification bar stays visible)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // inflate layout and set as content view
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // set toolbar as action bar
        setSupportActionBar(vb.toolbar)

        // find navigation host and setup toolbar navigation
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        val navController = navHost.navController
        vb.toolbar.setupWithNavController(navController)
    }
}
