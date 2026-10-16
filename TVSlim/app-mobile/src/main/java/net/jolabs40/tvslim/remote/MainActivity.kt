package net.jolabs40.tvslim.remote

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import net.jolabs40.tvslim.remote.ui.HardwareKeyHost
import net.jolabs40.tvslim.remote.ui.RemoteApp
import net.jolabs40.tvslim.remote.ui.theme.TvSlimRemoteTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity(), HardwareKeyHost {

    override var hardwareKeys: ((KeyEvent) -> Boolean)? = null

    /** Bumped by the launcher shortcut; the screen opens the remote on each new value. */
    private var remoteRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Not again after a rotation: the remote is already open, or was closed on purpose.
        if (savedInstanceState == null && intent?.action == ACTION_OPEN_REMOTE) remoteRequests++
        setContent {
            TvSlimRemoteTheme {
                RemoteApp(remoteRequests = remoteRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_REMOTE) remoteRequests++
    }

    // Volume keys reach the activity when no view used them, before the system turns them into a volume change.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        hardwareKeys?.invoke(event) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        hardwareKeys?.invoke(event) == true || super.onKeyUp(keyCode, event)

    companion object {
        /** The launcher shortcut's action (`res/xml/shortcuts.xml`). */
        const val ACTION_OPEN_REMOTE = "net.jolabs40.tvslim.remote.OPEN_REMOTE"
    }
}
