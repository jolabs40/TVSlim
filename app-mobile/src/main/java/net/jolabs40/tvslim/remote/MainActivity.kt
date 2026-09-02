package net.jolabs40.tvslim.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import net.jolabs40.tvslim.remote.ui.RemoteApp
import net.jolabs40.tvslim.remote.ui.theme.TvSlimRemoteTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TvSlimRemoteTheme {
                RemoteApp()
            }
        }
    }
}
