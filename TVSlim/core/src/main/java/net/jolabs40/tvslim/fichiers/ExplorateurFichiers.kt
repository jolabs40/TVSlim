package net.jolabs40.tvslim.fichiers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** State of the Files tab, shared by both apps. */
data class EtatExplorateur(
    val chemin: String = DOSSIER_DE_DEPART,
    /** Last listing of [chemin]; null until the folder has been opened. */
    val lecture: LectureDossier? = null,
    val chargement: Boolean = false,
    val raccourcis: List<Raccourci> = Raccourci.avecVolumes(emptyList()),
    /** Reading the picked items, then re-reading the folder; confirmation comes next. */
    val examen: Boolean = false,
    val confirmation: PlanDepot? = null,
    /** An upload, or a copy to the PC ([AvanceeDepot.sens] tells which). */
    val avancee: AvanceeDepot? = null,
    /** Result of the last upload or copy, still readable once the banner is gone. */
    val dernier: ResultatDepot? = null,
    /** A TV folder is listed in full before being copied or deleted, so the confirmation can say what it holds. */
    val inventaire: Boolean = false,
    /** Folder copy awaiting confirmation; a single file copy starts as soon as it is picked. */
    val rapatriement: PlanRapatriement? = null,
    val suppression: PlanSuppression? = null,
    val effacement: Boolean = false,
) {
    val entrees: List<EntreeDistante> get() = (lecture as? LectureDossier.Lue)?.entrees.orEmpty()
    val parent: String? get() = CheminDistant.parent(chemin)
    val etapes: List<EtapeChemin> get() = CheminDistant.etapes(chemin)
    val envoiEnCours: Boolean get() = avancee != null

    /**
     * One operation at a time: checking, confirming, uploading, copying and deleting never overlap (deleting
     * the folder an upload is writing to, for example).
     */
    val occupe: Boolean
        get() = examen || inventaire || confirmation != null || rapatriement != null || suppression != null ||
            effacement || envoiEnCours
}

/** Events each app turns into a localized message. */
sealed interface SignalFichiers {
    data object Occupe : SignalFichiers

    data class Refus(val refus: RefusDepot, val noms: List<String>) : SignalFichiers

    /** The picked local items cannot be read (protected folder, file gone). */
    data class LectureLocaleEchouee(val motif: String) : SignalFichiers

    data class Creation(val creation: CreationDossier) : SignalFichiers

    /** An upload, or a copy to the PC ([ResultatDepot.sens]). */
    data class Depot(val resultat: ResultatDepot) : SignalFichiers

    /** The contents of folder [nom] could not be read; nothing was copied or deleted. */
    data class ContenuIllisible(val nom: String, val refus: RefusLecture, val motif: String) : SignalFichiers

    data class Suppression(val suppression: SuppressionEntree) : SignalFichiers
}

/**
 * The Files tab without its UI: current folder, its listing, the transfer in progress, what is about to be
 * deleted. Shared by the companion and Windows, which only add how files are picked (and their destination on
 * disk) and the wording.
 *
 * A listing that comes back after the user moved elsewhere is ignored. An upload keeps running while the user
 * browses other folders; its commands and the listings take turns on the same connection.
 */
class ExplorateurFichiers(
    private val navigateur: NavigateurFichiers,
    private val portee: CoroutineScope,
    private val signaler: (SignalFichiers) -> Unit,
) {

    private val _etat = MutableStateFlow(EtatExplorateur())
    val etat: StateFlow<EtatExplorateur> = _etat.asStateFlow()

    private val annulation = AtomicBoolean(false)

    /** Bumped by each [oublier]: results from an earlier generation belong to another TV. */
    @Volatile
    private var generation = 0

    /** On entering the tab: reads the current folder and the volumes if not read yet. */
    fun demarrer() {
        val courant = _etat.value
        if (courant.lecture == null && !courant.chargement) {
            ouvrir(courant.chemin)
            lireRaccourcis()
        }
    }

    fun ouvrir(chemin: String) {
        val cible = CheminDistant.normaliser(chemin)
        val tour = generation
        // Reloading the same folder keeps the old listing until the answer; another folder clears it at once.
        _etat.update { it.copy(chemin = cible, chargement = true, lecture = if (it.chemin == cible) it.lecture else null) }
        portee.launch {
            val lue = navigateur.lister(cible)
            _etat.update { if (tour == generation && it.chemin == cible) it.copy(lecture = lue, chargement = false) else it }
        }
    }

    fun remonter() {
        _etat.value.parent?.let(::ouvrir)
    }

    /** Reloads the folder and the volumes, since a USB drive may have been plugged in. */
    fun actualiser() {
        ouvrir(_etat.value.chemin)
        lireRaccourcis()
    }

    /**
     * Checks what [preparer] gathers (picked files, a walked folder) for upload into the current folder.
     * Reading local files can be slow and can fail, so it runs here under the checking indicator.
     */
    fun examiner(preparer: suspend () -> LotLocal) {
        if (_etat.value.occupe) return signaler(SignalFichiers.Occupe)
        val destination = _etat.value.chemin
        val tour = generation
        _etat.update { it.copy(examen = true) }
        portee.launch {
            val lot = try {
                preparer()
            } catch (erreur: Exception) {
                _etat.update { it.copy(examen = false) }
                signaler(SignalFichiers.LectureLocaleEchouee(erreur.message ?: erreur.javaClass.simpleName))
                return@launch
            }
            val examen = navigateur.examiner(lot, destination)
            if (tour != generation) return@launch
            when (examen) {
                is ExamenDepot.Pret -> _etat.update { it.copy(examen = false, confirmation = examen.plan) }
                is ExamenDepot.Refuse -> {
                    _etat.update { it.copy(examen = false) }
                    signaler(SignalFichiers.Refus(examen.refus, examen.noms))
                }
            }
        }
    }

    fun annulerConfirmation() = _etat.update { it.copy(confirmation = null) }

    fun confirmer() {
        val plan = _etat.value.confirmation ?: return
        val tour = generation
        annulation.set(false)
        _etat.update {
            it.copy(confirmation = null, avancee = AvanceeDepot("", 0, plan.lot.fichiers.size, 0L, plan.lot.taille))
        }
        portee.launch {
            val resultat = navigateur.deposer(plan, annule = annulation::get) { avancee ->
                if (tour == generation) _etat.update { it.copy(avancee = avancee) }
            }
            if (tour != generation) return@launch
            _etat.update { it.copy(avancee = null, dernier = resultat) }
            signaler(SignalFichiers.Depot(resultat))
            // Shows the new files if the user is still in that folder.
            if (_etat.value.chemin == plan.destination) ouvrir(plan.destination)
        }
    }

    /** Stops the upload or copy at the next block; the partial file is kept on neither side. */
    fun annulerEnvoi() = annulation.set(true)

    /**
     * Copies [entree] from the current folder to [cible] as [nom]. A file starts right away: the Save As
     * dialog served as confirmation and already asked about overwriting. A folder is listed first and waits
     * for [confirmerRapatriement], since it may weigh gigabytes.
     */
    fun rapatrier(entree: EntreeDistante, cible: CibleLocale, nom: String) {
        if (_etat.value.occupe) return signaler(SignalFichiers.Occupe)
        val dossier = _etat.value.chemin
        val tour = generation
        _etat.update { it.copy(inventaire = entree.dossier) }
        portee.launch {
            val examen = navigateur.preparerRapatriement(dossier, entree, cible, nom)
            if (tour != generation) return@launch
            _etat.update { it.copy(inventaire = false) }
            when (examen) {
                is ExamenRapatriement.Pret ->
                    if (examen.plan.dossier) _etat.update { it.copy(rapatriement = examen.plan) } else copier(examen.plan)

                is ExamenRapatriement.Illisible -> signaler(SignalFichiers.ContenuIllisible(entree.nom, examen.refus, examen.motif))
            }
        }
    }

    fun confirmerRapatriement() {
        _etat.value.rapatriement?.let(::copier)
    }

    fun annulerRapatriement() = _etat.update { it.copy(rapatriement = null) }

    private fun copier(plan: PlanRapatriement) {
        val tour = generation
        annulation.set(false)
        _etat.update {
            it.copy(
                rapatriement = null,
                avancee = AvanceeDepot("", 0, plan.fichiers.size, 0L, plan.taille, SensTransfert.RECEPTION),
            )
        }
        portee.launch {
            val resultat = navigateur.rapatrier(plan, annule = annulation::get) { avancee ->
                if (tour == generation) _etat.update { it.copy(avancee = avancee) }
            }
            if (tour != generation) return@launch
            _etat.update { it.copy(avancee = null, dernier = resultat) }
            signaler(SignalFichiers.Depot(resultat))
        }
    }

    /** Prepares deleting [entree] from the current folder; nothing is deleted before [confirmerSuppression]. */
    fun demanderSuppression(entree: EntreeDistante) {
        if (_etat.value.occupe) return signaler(SignalFichiers.Occupe)
        val dossier = _etat.value.chemin
        val tour = generation
        _etat.update { it.copy(inventaire = entree.dossier && !entree.lien) }
        portee.launch {
            val examen = navigateur.preparerSuppression(dossier, entree)
            if (tour != generation) return@launch
            _etat.update { it.copy(inventaire = false) }
            when (examen) {
                is ExamenSuppression.Pret -> _etat.update { it.copy(suppression = examen.plan) }
                ExamenSuppression.Protege ->
                    signaler(SignalFichiers.Suppression(SuppressionEntree(IssueSuppression.PROTEGE, entree.nom)))

                is ExamenSuppression.Illisible -> signaler(SignalFichiers.ContenuIllisible(entree.nom, examen.refus, examen.motif))
            }
        }
    }

    fun confirmerSuppression() {
        val plan = _etat.value.suppression ?: return
        val tour = generation
        _etat.update { it.copy(suppression = null, effacement = true) }
        portee.launch {
            val issue = navigateur.supprimer(plan)
            if (tour != generation) return@launch
            _etat.update { it.copy(effacement = false) }
            signaler(SignalFichiers.Suppression(issue))
            // Reloaded even after a failure: a partly deleted folder shows what is left.
            val parent = CheminDistant.parent(plan.chemin)
            if (parent != null && _etat.value.chemin == parent) ouvrir(parent)
        }
    }

    fun annulerSuppression() = _etat.update { it.copy(suppression = null) }

    fun creerDossier(nom: String) {
        val parent = _etat.value.chemin
        val tour = generation
        portee.launch {
            val creation = navigateur.creerDossier(parent, nom)
            if (tour != generation) return@launch
            signaler(SignalFichiers.Creation(creation))
            if (creation.issue == IssueCreation.CREE && _etat.value.chemin == parent) ouvrir(parent)
        }
    }

    /** On disconnect or when switching TVs: nothing read so far applies to the next one. */
    fun oublier() {
        generation++
        annulation.set(true)
        _etat.value = EtatExplorateur()
    }

    private fun lireRaccourcis() {
        val tour = generation
        portee.launch {
            val raccourcis = navigateur.raccourcis()
            if (tour == generation) _etat.update { it.copy(raccourcis = raccourcis) }
        }
    }
}
