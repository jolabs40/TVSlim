package net.jolabs40.tvslim.fichiers

import java.io.Closeable
import java.io.OutputStream

/**
 * Où arrivent les fichiers copiés depuis le téléviseur : un dossier du disque, sous Windows. Les chemins sont
 * relatifs à ce dossier et séparés par `/` ; c'est la cible qui les adapte à son système de fichiers — un nom
 * d'Android peut porter un `:` que Windows refuse.
 */
interface CibleLocale {
    /** Un chemin de la cible tel qu'on le montre : « C:\Users\…\Downloads\Films ». Vide : la cible elle-même. */
    fun decrire(chemin: String = ""): String

    fun existe(chemin: String): Boolean

    /** Crée [chemin] et ses parents ; un dossier qui existe déjà n'est pas une erreur. */
    fun creerDossier(chemin: String)

    /** Ouvre l'écriture de [chemin]. Rien ne remplace ce qui s'y trouve avant [EcritureLocale.valider]. */
    fun ecrire(chemin: String): EcritureLocale
}

/**
 * Un fichier en cours d'écriture, provisoire tant qu'il n'est pas validé. Une copie arrêtée ou coupée ne laisse
 * donc ni fichier tronqué, ni fichier précédent écrasé : refermer sans valider efface ce qui était arrivé.
 */
interface EcritureLocale : Closeable {
    val flux: OutputStream

    /** Tout est arrivé : le fichier prend sa place — et celle d'un fichier du même nom —, daté de [date] (ms, 0 si inconnue). */
    fun valider(date: Long)
}

/** Un fichier à copier : où il est sur le téléviseur, où il arrive dans la cible. */
data class FichierDistant(val distant: String, val local: String, val taille: Long, val date: Long)

/** Une copie vers l'ordinateur, prête à partir. */
data class PlanRapatriement(
    /** Ce qu'on copie, sur le téléviseur : un fichier, ou un dossier et tout ce qu'il contient. */
    val source: String,
    val cible: CibleLocale,
    /** Ce qui arrive dans la cible : le nom du fichier, ou celui du dossier où va le reste. */
    val nom: String,
    val dossier: Boolean,
    val fichiers: List<FichierDistant>,
    /** Les dossiers à créer dans la cible, vides compris, parents d'abord. */
    val dossiers: List<String> = emptyList(),
    /** [nom] existe déjà dans la cible : un dossier y sera complété, ses fichiers du même nom remplacés. */
    val existant: Boolean = false,
) {
    val taille: Long get() = fichiers.sumOf { it.taille }

    /** Là où tout arrive, pour le dire : le dossier copié, ou celui qui reçoit le fichier. */
    val destination: String get() = cible.decrire(if (dossier) nom else "")
}

/** Pourquoi le contenu d'un dossier du téléviseur ne s'est pas lu. */
enum class RefusLecture { INTROUVABLE, REFUSE, ECHEC }

sealed interface ExamenRapatriement {
    data class Pret(val plan: PlanRapatriement) : ExamenRapatriement

    /** [motif] : ce qu'ont répondu la connexion ou le téléviseur, pour [RefusLecture.ECHEC]. */
    data class Illisible(val refus: RefusLecture, val motif: String = "") : ExamenRapatriement
}
