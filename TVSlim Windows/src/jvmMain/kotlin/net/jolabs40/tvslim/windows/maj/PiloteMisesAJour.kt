package net.jolabs40.tvslim.windows.maj

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.windows.data.PreferencesWindows
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.update_check_failed
import net.jolabs40.tvslim.windows.ressources.update_failed
import net.jolabs40.tvslim.windows.ressources.update_signature_invalid
import net.jolabs40.tvslim.windows.ui.MessageUi
import net.jolabs40.tvslim.windows.ui.texte

sealed interface PhaseMiseAJour {
    data object Inactive : PhaseMiseAJour
    data object Recherche : PhaseMiseAJour
    data object AJour : PhaseMiseAJour
    data class Disponible(val maj: MiseAJourDisponible) : PhaseMiseAJour
    data class Telechargement(val maj: MiseAJourDisponible, val progression: Float) : PhaseMiseAJour
    data class Verification(val maj: MiseAJourDisponible) : PhaseMiseAJour
    data class Installation(val maj: MiseAJourDisponible) : PhaseMiseAJour
    data class Echec(val message: MessageUi, val maj: MiseAJourDisponible?) : PhaseMiseAJour
}

data class EtatMiseAJour(
    val phase: PhaseMiseAJour = PhaseMiseAJour.Inactive,
    val verificationAuto: Boolean = true,
    val mode: ModeDistribution = ModeDistribution.DEVELOPPEMENT,
    val versionActuelle: String = "",
    /** "Later" hides the banner for this session. */
    val banniereEcartee: Boolean = false,
) {
    /** The update in progress, whatever the phase. */
    val miseAJour: MiseAJourDisponible?
        get() = when (val p = phase) {
            is PhaseMiseAJour.Disponible -> p.maj
            is PhaseMiseAJour.Telechargement -> p.maj
            is PhaseMiseAJour.Verification -> p.maj
            is PhaseMiseAJour.Installation -> p.maj
            is PhaseMiseAJour.Echec -> p.maj
            else -> null
        }
}

/**
 * Update checks and installs.
 *
 * Checks GitHub at startup (can be turned off in About). Nothing is downloaded until the user clicks Install,
 * and nothing is installed without a valid signature. Portable and development builds open the release page
 * instead of installing.
 */
class PiloteMisesAJour(
    private val preferences: PreferencesWindows,
    private val client: ClientGithub,
    private val installateur: InstallateurMiseAJour,
    private val distribution: Distribution,
    private val versionActuelle: Version,
    private val depot: String,
    private val quitter: () -> Unit,
    private val ouvrirLien: (String) -> Unit,
) : ViewModel() {

    private val _etat = MutableStateFlow(
        EtatMiseAJour(mode = distribution.mode, versionActuelle = versionActuelle.toString()),
    )
    val etat: StateFlow<EtatMiseAJour> = _etat.asStateFlow()

    private var travail: Job? = null

    init {
        viewModelScope.launch {
            val lues = preferences.lire()
            _etat.update { it.copy(verificationAuto = lues.verifierMisesAJour) }
            if (lues.verifierMisesAJour && distribution.mode != ModeDistribution.DEVELOPPEMENT) {
                verifier(discret = true)
            }
        }
    }

    /** [discret]: the startup check stays silent when up to date or when GitHub is unreachable. */
    fun verifier(discret: Boolean = false) {
        if (travail?.isActive == true) return
        travail = viewModelScope.launch {
            if (!discret) _etat.update { it.copy(phase = PhaseMiseAJour.Recherche) }
            val issue = try {
                Result.success(ChoixPublication.choisir(client.publications(), versionActuelle, depot))
            } catch (annulation: CancellationException) {
                throw annulation
            } catch (erreur: Exception) {
                Result.failure(erreur)
            }
            _etat.update { courant ->
                issue.fold(
                    onSuccess = { maj ->
                        when {
                            maj != null -> courant.copy(phase = PhaseMiseAJour.Disponible(maj), banniereEcartee = false)
                            discret -> courant
                            else -> courant.copy(phase = PhaseMiseAJour.AJour)
                        }
                    },
                    onFailure = { erreur ->
                        if (discret) {
                            courant
                        } else {
                            courant.copy(phase = PhaseMiseAJour.Echec(echec(Res.string.update_check_failed, erreur), null))
                        }
                    },
                )
            }
        }
    }

    /** Installs the update, or opens its download page when not running from the MSI install. */
    fun installer() {
        val maj = _etat.value.miseAJour ?: return
        val executable = distribution.executable
        if (distribution.mode != ModeDistribution.INSTALLEE || executable == null) {
            ouvrirLien(maj.page)
            return
        }
        if (travail?.isActive == true) return
        travail = viewModelScope.launch {
            _etat.update { it.copy(phase = PhaseMiseAJour.Telechargement(maj, 0f)) }
            try {
                val msi = installateur.preparer(
                    maj = maj,
                    surProgression = { p -> _etat.update { it.copy(phase = PhaseMiseAJour.Telechargement(maj, p)) } },
                    surVerification = { _etat.update { it.copy(phase = PhaseMiseAJour.Verification(maj)) } },
                )
                _etat.update { it.copy(phase = PhaseMiseAJour.Installation(maj)) }
                installateur.installerPuisRelancer(msi, executable)
                // Leaves time to read the restart message; the relay waits for us to exit.
                delay(DELAI_AVANT_FERMETURE_MS)
                quitter()
            } catch (annulation: CancellationException) {
                throw annulation
            } catch (invalide: InstallateurMiseAJour.SignatureInvalide) {
                _etat.update { it.copy(phase = PhaseMiseAJour.Echec(texte(Res.string.update_signature_invalid), maj)) }
            } catch (erreur: Exception) {
                _etat.update { it.copy(phase = PhaseMiseAJour.Echec(echec(Res.string.update_failed, erreur), maj)) }
            }
        }
    }

    fun ouvrirPage() = ouvrirLien(_etat.value.miseAJour?.page ?: "https://github.com/$depot/releases")

    fun ecarter() = _etat.update { it.copy(banniereEcartee = true) }

    fun majVerificationAuto(active: Boolean) {
        _etat.update { it.copy(verificationAuto = active) }
        viewModelScope.launch { preferences.majVerificationMisesAJour(active) }
    }

    private fun echec(ressource: org.jetbrains.compose.resources.StringResource, erreur: Throwable) =
        texte(ressource, erreur.message?.takeIf { it.isNotBlank() } ?: erreur.javaClass.simpleName)

    private companion object {
        const val DELAI_AVANT_FERMETURE_MS = 1_500L
    }
}
