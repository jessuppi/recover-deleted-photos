package top.thinapps.recoverdeletedphotos

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // temporary layout for theme testing only
        // this helps preview material 3 colors, typography, and dark mode
        // later replace this with your real main screen layout
        setContentView(R.layout.view_empty_state)
    }
}
