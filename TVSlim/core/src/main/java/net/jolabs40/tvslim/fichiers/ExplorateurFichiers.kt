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
    val avancee: AvanceeDepot? = null,
    /** Le bilan du dernier envoi, qui reste lisible une fois la bannière passée. */
    val dernier: ResultatDepot? = null,
) {
    val entrees: List<EntreeDistante> get() = (lecture as? LectureDossier.Lue)?.entrees.orEmpty()
    val parent: String? get() = CheminDistant.parent(chemin)
    val etapes: List<EtapeChemin> get() = CheminDistant.etapes(chemin)
    val envoiEnCours: Boolean get() = avancee != null

    /** Un envoi à la fois : examen, confirmation et envoi se suivent sans se chevaucher. */
    val occupe: Boolean get() = examen || confirmation != null || envoiEnCours
}

/** Ce que chaque application dit à la personne, dans sa langue. */
sealed interface SignalFichiers {
    data object Occupe : SignalFichiers

    data class Refus(val refus: RefusDepot, val noms: List<String>) : SignalFichiers

    /** Ce qu'on a choisi ne se lit pas sur place : dossier protégé, fichier disparu. */
    data class LectureLocaleEchouee(val motif: String) : SignalFichiers

    data class Creation(val creation: CreationDossier) : SignalFichiers

    data class Depot(val resultat: ResultatDepot) : SignalFichiers
}

/**
 * L'onglet Fichiers, sans son écran : où l'on est, ce qu'on y lit, l'envoi en cours. Partagé par le compagnon et
 * par Windows, qui n'y ajoutent que la façon de choisir des fichiers et les mots pour le dire.
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

    /** Arrête l'envoi au prochain bloc ; le fichier entamé n'est pas gardé. */
    fun annulerEnvoi() = annulation.set(true)

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
