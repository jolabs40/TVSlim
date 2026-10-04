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

/**
 * Ce qu'on a choisi sur le téléphone, en attente du dossier du téléviseur où le déposer : des documents un à un,
 * ou un dossier entier ([arbre]). [nom] est celui qu'on montre — du document quand il est seul, du dossier.
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
 * L'onglet Fichiers : l'explorateur du noyau, partagé avec Windows, et ce que le téléphone y ajoute — les
 * documents désignés dans le sélecteur d'Android, et les mots pour dire ce qui s'est passé.
 *
 * Sur le téléphone, on choisit d'abord **quoi** envoyer, puis **où** : ce qui a été désigné attend
 * ([enAttente]) qu'on ouvre le dossier de destination et qu'on l'envoie. Naviguer d'abord, puis « Envoyer
 * ici », ne disait pas qu'on était en train de choisir une destination (remarque de l'utilisateur, 2026-10-04).
 *
 * Vit à côté du [RemoteViewModel], comme les permissions et la configuration : il n'en partage que la portée
 * et la bannière.
 */
class PiloteFichiers(
    private val contexte: Context,
    client: ClientAdb,
    private val portee: CoroutineScope,
    private val afficher: (String) -> Unit,
) {

    val explorateur = ExplorateurFichiers(NavigateurFichiers(client, client), portee) { afficher(it.message()) }

    private val _enAttente = MutableStateFlow<EnvoiEnAttente?>(null)
    val enAttente: StateFlow<EnvoiEnAttente?> = _enAttente.asStateFlow()

    init {
        // Ce qu'on a lu appartient au téléviseur : se déconnecter, ou en joindre un autre, l'oublie. Une reprise
        // sur le même téléviseur, non. Ce qu'on s'apprêtait à envoyer aussi : la destination n'existe plus.
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

    /** Des documents choisis un à un, qui attendent leur dossier de destination. */
    fun choisirDocuments(documents: List<Uri>) {
        if (documents.isEmpty()) return
        portee.launch {
            val nom = withContext(Dispatchers.IO) { nomDuDocument(contexte, documents.first()) }
            _enAttente.value = EnvoiEnAttente(documents = documents.distinct(), nom = nom)
        }
    }

    /** Un dossier entier, sous-dossiers compris, qui attend le sien. */
    fun choisirDossier(arbre: Uri) {
        portee.launch {
            val nom = withContext(Dispatchers.IO) { nomDuDossier(contexte, arbre) }
            _enAttente.value = EnvoiEnAttente(arbre = arbre, nom = nom)
        }
    }

    /**
     * Le dossier affiché est la destination : ce qui attend s'examine, puis se confirme. Rien ne se perd d'ici
     * là — une confirmation refusée, ou un dossier qui ne convient pas, ramène au choix de la destination.
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

    /** L'envoi part : ce qui attendait est servi. */
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

        // Les échecs en détail restent dans la carte de l'onglet : une bannière de téléphone n'a que deux lignes.
        is SignalFichiers.Depot -> bilan(resultat)

        // Copier et supprimer ne s'offrent encore que sous Windows ; le noyau, partagé, sait déjà les dire.
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
