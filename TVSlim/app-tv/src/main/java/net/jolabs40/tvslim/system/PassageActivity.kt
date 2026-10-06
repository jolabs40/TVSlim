package net.jolabs40.tvslim.system

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Le service de démarrage que lance StartLight à l'allumage du téléviseur (action
 * `net.jolabs40.startlight.action.SERVICE_DEMARRAGE`) : une activité **invisible**, qui lance le gardien
 * et se referme avant d'avoir rien affiché (`Theme.NoDisplay`).
 *
 * Une activité, parce que c'est la seule porte que le gestionnaire de démarrage de TCL laisse ouverte à
 * une application tierce — cf. [GardienDemarrage]. StartLight, écran d'accueil au premier plan, a le droit
 * d'en ouvrir une ; il ne le fait que si on l'a activé dans ses réglages.
 *
 * ⚠️ Ouvrir une activité met en pause celle du premier plan : à l'allumage, rien ne joue encore. Ce
 * n'est pas une porte à ouvrir n'importe quand.
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
        /** Qui a ouvert la porte, pour les traces : StartLight le dit. */
        const val EXTRA_PORTE = "porte"
    }
}
