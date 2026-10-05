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
 * L'onglet Fichiers : l'explorateur du noyau, partagé avec le compagnon, et ce que Windows y ajoute — les
 * fichiers du disque, glissés ou choisis, le dossier où copier ceux du téléviseur, et les mots pour dire ce qui
 * s'est passé.
 */
class PiloteFichiers(
    client: ClientAdb,
    portee: CoroutineScope,
    private val afficher: (MessageUi) -> Unit,
    /** Un envoi ou une copie arrivés au bout : le bandeau de soutien peut se montrer. */
    private val remercier: () -> Unit,
) {

    val explorateur = ExplorateurFichiers(NavigateurFichiers(client, client, client), portee) { signal ->
        afficher(signal.message())
        if (InvitationSoutien.merite(signal)) remercier()
    }

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

    /** Ce qu'on glisse dans la fenêtre, ou qu'on choisit, part dans le dossier où l'on est. */
    fun deposer(elements: List<File>) {
        if (elements.isEmpty()) return
        explorateur.examiner { withContext(Dispatchers.IO) { lotDepuis(elements) } }
    }

    /**
     * Copie [entree] vers le disque. [choix] est le fichier désigné dans « Enregistrer sous » pour un fichier — son
     * nom peut différer —, le dossier qui recevra l'autre pour un dossier.
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
        /** Au-delà, la bannière déborderait : la carte de l'onglet garde la liste complète. */
        const val MAX_ECHECS = 4
    }
}
