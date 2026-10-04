package net.jolabs40.tvslim.remote.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * L'onglet Fichiers : l'explorateur du noyau, partagé avec Windows, et ce que le téléphone y ajoute — les
 * documents désignés dans le sélecteur d'Android, et les mots pour dire ce qui s'est passé.
 *
 * Vit à côté du [RemoteViewModel], comme les permissions et la configuration : il n'en partage que la portée
 * et la bannière.
 */
class PiloteFichiers(
    private val contexte: Context,
    client: ClientAdb,
    portee: CoroutineScope,
    private val afficher: (String) -> Unit,
) {

    val explorateur = ExplorateurFichiers(NavigateurFichiers(client, client), portee) { afficher(it.message()) }

    init {
        // Ce qu'on a lu appartient au téléviseur : se déconnecter, ou en joindre un autre, l'oublie. Une reprise
        // sur le même téléviseur, non.
        portee.launch {
            client.connexion
                .map { if (it.etat == EtatConnexion.DECONNECTE) "" else it.hote }
                .distinctUntilChanged()
                .drop(1)
                .collect { explorateur.oublier() }
        }
    }

    /** Des documents choisis un à un, envoyés dans le dossier où l'on est. */
    fun deposerDocuments(documents: List<Uri>) {
        if (documents.isEmpty()) return
        explorateur.examiner { withContext(Dispatchers.IO) { lotDeDocuments(contexte, documents) } }
    }

    /** Un dossier entier, sous-dossiers compris. */
    fun deposerDossier(arbre: Uri) {
        explorateur.examiner { withContext(Dispatchers.IO) { lotDeDossier(contexte, arbre) } }
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
