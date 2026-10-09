package net.jolabs40.tvslim.remote.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.fichiers.ExplorateurFichiers
import net.jolabs40.tvslim.fichiers.IssueCreation
import net.jolabs40.tvslim.fichiers.IssueSuppression
import net.jolabs40.tvslim.fichiers.NavigateurFichiers
import net.jolabs40.tvslim.fichiers.RefusDepot
import net.jolabs40.tvslim.fichiers.RefusLecture
import net.jolabs40.tvslim.fichiers.ResultatDepot
import net.jolabs40.tvslim.fichiers.SignalFichiers
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.ClientAdb
import net.jolabs40.tvslim.remote.adb.EtatConnexion
import net.jolabs40.tvslim.remote.fichiers.lotDeDocuments
import net.jolabs40.tvslim.remote.fichiers.lotDeDossier
import net.jolabs40.tvslim.remote.fichiers.nomDuDocument
import net.jolabs40.tvslim.remote.fichiers.nomDuDossier
import net.jolabs40.tvslim.soutien.InvitationSoutien

/**
 * Items picked on the phone, waiting for a destination folder on the TV: individual documents or a whole
 * folder ([arbre]). [nom] is the name shown (the first document's, or the folder's).
 */
data class EnvoiEnAttente(
    val documents: List<Uri> = emptyList(),
    val arbre: Uri? = null,
    val nom: String,
) {
    val dossier: Boolean get() = arbre != null
    val nombre: Int get() = documents.size
}

/**
 * Files tab: the core's file explorer (shared with Windows) plus the phone-specific parts, picking documents
 * with the Android picker and turning results into messages.
 *
 * On the phone the user picks what to send first, then where: the selection waits in [enAttente] until the
 * destination folder is opened and the upload confirmed. Browsing first did not make it clear that a
 * destination was being chosen.
 *
 * Shares only the coroutine scope and the banner with [RemoteViewModel].
 */
class PiloteFichiers(
    private val contexte: Context,
    client: ClientAdb,
    private val portee: CoroutineScope,
    private val afficher: (String) -> Unit,
    /** Called after a completed upload; may show the support banner. */
    private val remercier: () -> Unit,
) {

    val explorateur = ExplorateurFichiers(NavigateurFichiers(client, client), portee) { signal ->
        afficher(signal.message())
        if (InvitationSoutien.merite(signal)) remercier()
    }

    private val _enAttente = MutableStateFlow<EnvoiEnAttente?>(null)
    val enAttente: StateFlow<EnvoiEnAttente?> = _enAttente.asStateFlow()

    init {
        // Forget what was read, and any pending upload, on disconnect or when switching TVs. A reconnect to the
        // same TV keeps it.
        portee.launch {
            client.connexion
                .map { if (it.etat == EtatConnexion.DECONNECTE) "" else it.hote }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    _enAttente.value = null
                    explorateur.oublier()
                }
        }
    }

    fun choisirDocuments(documents: List<Uri>) {
        if (documents.isEmpty()) return
        portee.launch {
            val nom = withContext(Dispatchers.IO) { nomDuDocument(contexte, documents.first()) }
            _enAttente.value = EnvoiEnAttente(documents = documents.distinct(), nom = nom)
        }
    }

    /** Picks a whole folder, subfolders included. */
    fun choisirDossier(arbre: Uri) {
        portee.launch {
            val nom = withContext(Dispatchers.IO) { nomDuDossier(contexte, arbre) }
            _enAttente.value = EnvoiEnAttente(arbre = arbre, nom = nom)
        }
    }

    /**
     * Uses the displayed folder as destination: the pending items are examined, then confirmed. A declined
     * confirmation or an unsuitable folder returns to choosing the destination, keeping the selection.
     */
    fun envoyerIci() {
        val attente = _enAttente.value ?: return
        val arbre = attente.arbre
        explorateur.examiner {
            withContext(Dispatchers.IO) {
                if (arbre != null) lotDeDossier(contexte, arbre) else lotDeDocuments(contexte, attente.documents)
            }
        }
    }

    fun abandonnerEnvoi() {
        _enAttente.value = null
    }

    fun confirmer() {
        _enAttente.value = null
        explorateur.confirmer()
    }

    private fun SignalFichiers.message(): String = when (this) {
        SignalFichiers.Occupe -> contexte.getString(R.string.files_busy)
        is SignalFichiers.Refus -> when (refus) {
            RefusDepot.VIDE -> contexte.getString(R.string.files_refused_empty)
            RefusDepot.NOM_INVALIDE -> contexte.getString(R.string.files_refused_name, noms.joinToString(", "))
            RefusDepot.NATURE_DIFFERENTE -> contexte.getString(R.string.files_refused_kind, noms.joinToString(", "))
            RefusDepot.DESTINATION_ILLISIBLE -> contexte.getString(R.string.files_refused_destination)
        }

        is SignalFichiers.LectureLocaleEchouee -> contexte.getString(R.string.files_local_failed, motif)
        is SignalFichiers.Creation -> when (creation.issue) {
            IssueCreation.CREE -> contexte.getString(R.string.files_folder_created, creation.nom)
            IssueCreation.NOM_INVALIDE -> contexte.getString(R.string.files_folder_invalid)
            IssueCreation.EXISTE -> contexte.getString(R.string.files_folder_exists, creation.nom)
            IssueCreation.ECHEC -> contexte.getString(R.string.files_folder_failed, creation.detail)
        }

        // Per-file failures stay in the tab's card: a phone banner only has two lines.
        is SignalFichiers.Depot -> bilan(resultat)

        // Copy and delete are only offered on Windows so far, but the shared core can report them.
        is SignalFichiers.ContenuIllisible -> when (refus) {
            RefusLecture.INTROUVABLE -> contexte.getString(R.string.files_content_not_found, nom)
            RefusLecture.REFUSE -> contexte.getString(R.string.files_content_denied, nom)
            RefusLecture.ECHEC -> contexte.getString(R.string.files_content_failed, nom, motif)
        }

        is SignalFichiers.Suppression -> when (suppression.issue) {
            IssueSuppression.SUPPRIME -> contexte.getString(R.string.files_deleted, suppression.nom)
            IssueSuppression.PROTEGE -> contexte.getString(R.string.files_delete_protected, suppression.nom)
            IssueSuppression.ECHEC ->
                contexte.getString(R.string.files_delete_failed, suppression.nom, suppression.detail.lines().first())
        }
    }

    private fun bilan(resultat: ResultatDepot): String = when {
        resultat.annule -> contexte.getString(R.string.files_sent_cancelled, resultat.envoyes, resultat.nombre)
        resultat.interrompu -> contexte.getString(R.string.files_sent_interrupted, resultat.envoyes, resultat.nombre)
        else -> contexte.getString(R.string.files_sent, resultat.envoyes, resultat.nombre, resultat.destination)
    }
}
