package net.jolabs40.tvslim.system

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Boot hook that StartLight starts at power-on (action `net.jolabs40.startlight.action.SERVICE_DEMARRAGE`):
 * an invisible activity (`Theme.NoDisplay`) that starts the guard and finishes before drawing anything.
 *
 * An activity because it is the only entry point TCL's boot manager leaves open to third-party apps (see
 * [GardienDemarrage]). StartLight, as the foreground launcher, may start one, and only does so when enabled
 * in its settings.
 *
 * Starting an activity pauses the foreground one. Fine at power-on when nothing is playing yet, not at any
 * other time.
 */
@AndroidEntryPoint
class PassageActivity : ComponentActivity() {

    @Inject lateinit var gardien: GardienDemarrage

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        gardien.lancer(intent?.getStringExtra(EXTRA_PORTE) ?: "activité")
        finish()
    }

    private companion object {
        /** Caller name for the logs, set by StartLight. */
        const val EXTRA_PORTE = "porte"
    }
}
