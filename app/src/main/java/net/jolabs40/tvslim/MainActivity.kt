package net.jolabs40.tvslim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import net.jolabs40.tvslim.ui.TvSlimApp
import net.jolabs40.tvslim.ui.theme.TvSlimTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvSlimTheme {
                TvSlimApp()
            }
        }
    }
}
