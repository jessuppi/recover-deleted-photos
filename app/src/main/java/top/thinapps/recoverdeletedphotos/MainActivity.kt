package top.thinapps.recoverdeletedphotos

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ui-only shell for 0.5.0: toolbar + static chips + empty state
        setContentView(R.layout.activity_main)
    }
}
