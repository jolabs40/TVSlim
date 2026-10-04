package net.jolabs40.tvslim.fichiers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Ce que l'onglet Fichiers montre, dans les deux applications. */
data class EtatExplorateur(
    val chemin: String = DOSSIER_DE_DEPART,
    /** La dernière lecture de [chemin] ; aucune tant qu'on n'y est pas encore entré. */
    val lecture: LectureDossier? = null,
    val chargement: Boolean = false,
    val raccourcis: List<Raccourci> = Raccourci.avecVolumes(emptyList()),
    /** Ce qu'on a choisi se lit, puis le dossier se relit : la confirmation vient après. */
    val examen: Boolean = false,
    val confirmation: PlanDepot? = null,
    /** Un envoi, ou une copie vers l'ordinateur : [AvanceeDepot.sens] le dit. */
    val avancee: AvanceeDepot? = null,
    /** Le bilan du dernier envoi ou de la dernière copie, qui reste lisible une fois la bannière passée. */
    val dernier: ResultatDepot? = null,
    /** Un dossier du téléviseur se lit en entier avant d'être copié ou effacé : la confirmation dit ce qu'il contient. */
    val inventaire: Boolean = false,
    /** La copie d'un dossier, en attente de confirmation ; celle d'un fichier part aussitôt choisie. */
    val rapatriement: PlanRapatriement? = null,
    val suppression: PlanSuppression? = null,
    val effacement: Boolean = false,
) {
    val entrees: List<EntreeDistante> get() = (lecture as? LectureDossier.Lue)?.entrees.orEmpty()
    val parent: String? get() = CheminDistant.parent(chemin)
    val etapes: List<EtapeChemin> get() = CheminDistant.etapes(chemin)
    val envoiEnCours: Boolean get() = avancee != null

    /**
     * Une opération à la fois : examen, confirmation, envoi, copie et suppression se suivent sans se chevaucher —
     * effacer le dossier où arrive un envoi, par exemple.
     */
    val occupe: Boolean
        get() = examen || inventaire || confirmation != null || rapatriement != null || suppression != null ||
            effacement || envoiEnCours
}

/** Ce que chaque application dit à la personne, dans sa langue. */
sealed interface SignalFichiers {
    data object Occupe : SignalFichiers

    data class Refus(val refus: RefusDepot, val noms: List<String>) : SignalFichiers

    /** Ce qu'on a choisi ne se lit pas sur place : dossier protégé, fichier disparu. */
    data class LectureLocaleEchouee(val motif: String) : SignalFichiers

    data class Creation(val creation: CreationDossier) : SignalFichiers

    /** Un envoi, ou une copie vers l'ordinateur ([ResultatDepot.sens]). */
    data class Depot(val resultat: ResultatDepot) : SignalFichiers

    /** Le contenu du dossier [nom] ne s'est pas lu : rien n'a été copié ni effacé. */
    data class ContenuIllisible(val nom: String, val refus: RefusLecture, val motif: String) : SignalFichiers

    data class Suppression(val suppression: SuppressionEntree) : SignalFichiers
}

/**
 * L'onglet Fichiers, sans son écran : où l'on est, ce qu'on y lit, l'envoi ou la copie en cours, ce qu'on va
 * effacer. Partagé par le compagnon et par Windows, qui n'y ajoutent que la façon de choisir des fichiers — et
 * leur destination sur le disque — et les mots pour le dire.
 *
 * Une lecture qui revient après qu'on est passé ailleurs est ignorée ; un envoi suit son cours pendant qu'on
 * parcourt d'autres dossiers — ses commandes et les lectures se partagent la même connexion, chacune son tour.
 */
class ExplorateurFichiers(
    private val navigateur: NavigateurFichiers,
    private val portee: CoroutineScope,
    private val signaler: (SignalFichiers) -> Unit,
) {

    private val _etat = MutableStateFlow(EtatExplorateur())
    val etat: StateFlow<EtatExplorateur> = _etat.asStateFlow()

    private val annulation = AtomicBoolean(false)

    /** Change à chaque [oublier] : ce qui revient d'avant appartient à un autre téléviseur. */
    @Volatile
    private var generation = 0

    /** À l'arrivée sur l'onglet : le dossier courant et les volumes, s'ils n'ont pas encore été lus. */
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
        // Relire le même dossier garde ce qu'on y voyait jusqu'à la réponse ; en changer le vide aussitôt.
        _etat.update { it.copy(chemin = cible, chargement = true, lecture = if (it.chemin == cible) it.lecture else null) }
        portee.launch {
            val lue = navigateur.lister(cible)
            _etat.update { if (tour == generation && it.chemin == cible) it.copy(lecture = lue, chargement = false) else it }
        }
    }

    fun remonter() {
        _etat.value.parent?.let(::ouvrir)
    }

    /** Relit le dossier et les volumes : une clé USB a pu arriver entre-temps. */
    fun actualiser() {
        ouvrir(_etat.value.chemin)
        lireRaccourcis()
    }

    /**
     * Examine ce que [preparer] rassemble — fichiers choisis, dossier parcouru — pour l'envoyer dans le dossier
     * courant. La lecture sur place peut prendre du temps, et échouer : elle se fait ici, sous l'indicateur
     * d'examen.
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
            // Ce qu'on vient de déposer apparaît, si l'on regarde encore là.
            if (_etat.value.chemin == plan.destination) ouvrir(plan.destination)
        }
    }

    /** Arrête l'envoi ou la copie au prochain bloc ; le fichier entamé n'est gardé ni d'un côté ni de l'autre. */
    fun annulerEnvoi() = annulation.set(true)

    /**
     * Copie [entree], prise dans le dossier courant, vers [cible] sous le nom [nom]. Un fichier part aussitôt :
     * la fenêtre « Enregistrer sous » a tenu lieu de confirmation, et demandé s'il fallait en remplacer un. Un
     * dossier se lit d'abord, puis attend [confirmerRapatriement] : il peut peser des gigaoctets.
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

    /** Prépare l'effacement de [entree], prise dans le dossier courant : rien ne s'efface sans [confirmerSuppression]. */
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
            // Relu même après un échec : un dossier à moitié effacé montre ce qui reste.
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

    /** À la déconnexion, ou en passant à un autre téléviseur : rien de ce qu'on a lu ne vaut pour le suivant. */
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
