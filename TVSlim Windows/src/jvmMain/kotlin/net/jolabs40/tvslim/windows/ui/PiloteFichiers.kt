package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.fichiers.EntreeDistante
import net.jolabs40.tvslim.fichiers.ExplorateurFichiers
import net.jolabs40.tvslim.fichiers.IssueCreation
import net.jolabs40.tvslim.fichiers.IssueSuppression
import net.jolabs40.tvslim.fichiers.NavigateurFichiers
import net.jolabs40.tvslim.fichiers.RefusDepot
import net.jolabs40.tvslim.fichiers.RefusLecture
import net.jolabs40.tvslim.fichiers.ResultatDepot
import net.jolabs40.tvslim.fichiers.SensTransfert
import net.jolabs40.tvslim.fichiers.SignalFichiers
import net.jolabs40.tvslim.soutien.InvitationSoutien
import net.jolabs40.tvslim.windows.adb.ClientAdb
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.fichiers.CibleDisque
import net.jolabs40.tvslim.windows.fichiers.lotDepuis
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.files_refused_silent_line
import net.jolabs40.tvslim.windows.ressources.files_busy
import net.jolabs40.tvslim.windows.ressources.files_content_denied
import net.jolabs40.tvslim.windows.ressources.files_content_failed
import net.jolabs40.tvslim.windows.ressources.files_content_not_found
import net.jolabs40.tvslim.windows.ressources.files_copied
import net.jolabs40.tvslim.windows.ressources.files_copied_cancelled
import net.jolabs40.tvslim.windows.ressources.files_copied_interrupted
import net.jolabs40.tvslim.windows.ressources.files_delete_failed
import net.jolabs40.tvslim.windows.ressources.files_delete_protected
import net.jolabs40.tvslim.windows.ressources.files_deleted
import net.jolabs40.tvslim.windows.ressources.files_folder_created
import net.jolabs40.tvslim.windows.ressources.files_folder_exists
import net.jolabs40.tvslim.windows.ressources.files_folder_failed
import net.jolabs40.tvslim.windows.ressources.files_folder_invalid
import net.jolabs40.tvslim.windows.ressources.files_local_failed
import net.jolabs40.tvslim.windows.ressources.files_refused_destination
import net.jolabs40.tvslim.windows.ressources.files_refused_empty
import net.jolabs40.tvslim.windows.ressources.files_refused_kind
import net.jolabs40.tvslim.windows.ressources.files_refused_name
import net.jolabs40.tvslim.windows.ressources.files_sent
import net.jolabs40.tvslim.windows.ressources.files_sent_cancelled
import net.jolabs40.tvslim.windows.ressources.files_sent_interrupted
import java.io.File

/**
 * Files tab: the core's explorer, shared with the companion, plus the Windows side (local files dropped or
 * picked, the destination of copies from the TV) and the messages.
 */
class PiloteFichiers(
    client: ClientAdb,
    portee: CoroutineScope,
    private val afficher: (MessageUi) -> Unit,
    /** An upload or copy completed: the support banner may show. */
    private val remercier: () -> Unit,
) {

    val explorateur = ExplorateurFichiers(NavigateurFichiers(client, client, client), portee) { signal ->
        afficher(signal.message())
        if (InvitationSoutien.merite(signal)) remercier()
    }

    init {
        // What was read belongs to the TV: forget it on disconnect or when switching TVs, but not when
        // reconnecting to the same one.
        portee.launch {
            client.connexion
                .map { if (it.etat == EtatConnexion.DECONNECTE) "" else it.hote }
                .distinctUntilChanged()
                .drop(1)
                .collect { explorateur.oublier() }
        }
    }

    /** Uploads dropped or picked files to the current folder. */
    fun deposer(elements: List<File>) {
        if (elements.isEmpty()) return
        explorateur.examiner { withContext(Dispatchers.IO) { lotDepuis(elements) } }
    }

    /**
     * Copies [entree] to disk. For a file, [choix] is the file picked in the save dialog (its name may differ);
     * for a folder, it is the folder that receives it.
     */
    fun copier(entree: EntreeDistante, choix: File) {
        val absolu = choix.absoluteFile
        if (entree.dossier) {
            explorateur.rapatrier(entree, CibleDisque(absolu), entree.nom)
        } else {
            explorateur.rapatrier(entree, CibleDisque(absolu.parentFile ?: return), absolu.name)
        }
    }

    private fun SignalFichiers.message(): MessageUi = when (this) {
        SignalFichiers.Occupe -> texte(Res.string.files_busy)
        is SignalFichiers.Refus -> when (refus) {
            RefusDepot.VIDE -> texte(Res.string.files_refused_empty)
            RefusDepot.NOM_INVALIDE -> texte(Res.string.files_refused_name, noms.joinToString(", "))
            RefusDepot.NATURE_DIFFERENTE -> texte(Res.string.files_refused_kind, noms.joinToString(", "))
            RefusDepot.DESTINATION_ILLISIBLE -> texte(Res.string.files_refused_destination)
        }

        is SignalFichiers.LectureLocaleEchouee -> texte(Res.string.files_local_failed, motif)
        is SignalFichiers.Creation -> when (creation.issue) {
            IssueCreation.CREE -> texte(Res.string.files_folder_created, creation.nom)
            IssueCreation.NOM_INVALIDE -> texte(Res.string.files_folder_invalid)
            IssueCreation.EXISTE -> texte(Res.string.files_folder_exists, creation.nom)
            IssueCreation.ECHEC -> texte(Res.string.files_folder_failed, MessageUi.Brut(creation.detail))
        }

        is SignalFichiers.Depot -> MessageUi.Lignes(
            listOf(bilan(resultat)) +
                resultat.echecs.take(MAX_ECHECS).map {
                    if (it.motif.isBlank()) texte(Res.string.files_refused_silent_line, it.chemin) else MessageUi.Brut("${it.chemin} : ${it.motif}")
                },
        )

        is SignalFichiers.ContenuIllisible -> when (refus) {
            RefusLecture.INTROUVABLE -> texte(Res.string.files_content_not_found, nom)
            RefusLecture.REFUSE -> texte(Res.string.files_content_denied, nom)
            RefusLecture.ECHEC -> texte(Res.string.files_content_failed, nom, MessageUi.Brut(motif))
        }

        is SignalFichiers.Suppression -> when (suppression.issue) {
            IssueSuppression.SUPPRIME -> texte(Res.string.files_deleted, suppression.nom)
            IssueSuppression.PROTEGE -> texte(Res.string.files_delete_protected, suppression.nom)
            IssueSuppression.ECHEC ->
                texte(Res.string.files_delete_failed, suppression.nom, MessageUi.Brut(suppression.detail.lines().first()))
        }
    }

    private fun bilan(resultat: ResultatDepot): MessageUi = when (resultat.sens) {
        SensTransfert.ENVOI -> when {
            resultat.annule -> texte(Res.string.files_sent_cancelled, resultat.envoyes, resultat.nombre)
            resultat.interrompu -> texte(Res.string.files_sent_interrupted, resultat.envoyes, resultat.nombre)
            else -> texte(Res.string.files_sent, resultat.envoyes, resultat.nombre, resultat.destination)
        }

        SensTransfert.RECEPTION -> when {
            resultat.annule -> texte(Res.string.files_copied_cancelled, resultat.envoyes, resultat.nombre)
            resultat.interrompu -> texte(Res.string.files_copied_interrupted, resultat.envoyes, resultat.nombre)
            else -> texte(Res.string.files_copied, resultat.envoyes, resultat.nombre, resultat.destination)
        }
    }

    private companion object {
        /** More would overflow the snackbar; the tab's card keeps the full list. */
        const val MAX_ECHECS = 4
    }
}
