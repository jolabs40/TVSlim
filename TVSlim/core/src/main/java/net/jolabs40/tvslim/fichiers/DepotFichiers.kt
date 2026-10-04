package net.jolabs40.tvslim.fichiers

import java.io.InputStream

/**
 * Un fichier de l'ordinateur ou du téléphone, à envoyer au téléviseur. Chaque application dit d'où il vient :
 * le disque sous Windows, un document du sélecteur d'Android sur le téléphone.
 */
interface FichierLocal {
    /** Son chemin dans le lot, séparé par `/` : « Photos/2024/plage.jpg » pour un fichier d'un dossier envoyé. */
    val chemin: String

    /** En octets ; 0 quand la source ne le dit pas, ce qui arrive à certains fournisseurs de documents d'Android. */
    val taille: Long

    /** Dernière modification, en millisecondes ; 0 quand on l'ignore. */
    val date: Long

    /** Ouvre le contenu, que referme celui qui l'a ouvert. */
    fun ouvrir(): InputStream
}

/**
 * Ce qu'on a choisi d'envoyer : des fichiers, et des dossiers — un dossier vide compris, qu'aucun fichier ne
 * ferait naître.
 */
data class LotLocal(
    val fichiers: List<FichierLocal>,
    val dossiers: List<String> = emptyList(),
) {
    val taille: Long get() = fichiers.sumOf { it.taille }

    val vide: Boolean get() = fichiers.isEmpty() && dossiers.isEmpty()

    /**
     * Ce qui arrive directement dans le dossier de destination, et si c'est un dossier : un dossier envoyé n'y
     * pose que son nom, ses fichiers vont dedans.
     */
    val racines: Map<String, Boolean>
        get() = buildMap {
            dossiers.forEach { put(it.substringBefore('/'), true) }
            fichiers.forEach { fichier ->
                val premier = fichier.chemin.substringBefore('/')
                if (premier != fichier.chemin) put(premier, true) else if (premier !in this) put(premier, false)
            }
        }

    /** Tous les dossiers à créer, ceux qui contiennent les fichiers compris, du moins profond au plus profond. */
    val dossiersACreer: List<String>
        get() {
            val tous = sortedSetOf<String>()
            (dossiers + fichiers.mapNotNull { it.chemin.substringBeforeLast('/', "").ifEmpty { null } })
                .forEach { dossier ->
                    var courant = ""
                    dossier.split('/').forEach { etape ->
                        courant = if (courant.isEmpty()) etape else "$courant/$etape"
                        tous += courant
                    }
                }
            return tous.sortedBy { chemin -> chemin.count { it == '/' } }
        }
}

/** Pourquoi un envoi est écarté avant que rien ne parte. */
enum class RefusDepot {
    VIDE,

    /** Un nom que le téléviseur ne prendrait pas, ou qui casserait la lecture de son dossier. */
    NOM_INVALIDE,

    /** Un fichier là où le téléviseur a un dossier du même nom, ou l'inverse. */
    NATURE_DIFFERENTE,

    /** Le dossier de destination ne se lit pas : rien ne dit ce qu'on y écraserait. */
    DESTINATION_ILLISIBLE,
}

/** Un envoi examiné, prêt à être soumis à confirmation. */
data class PlanDepot(
    val destination: String,
    val lot: LotLocal,
    /** Les éléments du lot que le téléviseur a déjà : un fichier y sera remplacé, un dossier complété. */
    val existants: List<String>,
)

sealed interface ExamenDepot {
    data class Pret(val plan: PlanDepot) : ExamenDepot

    /** [noms] : les éléments en cause, quand il y en a. */
    data class Refuse(val refus: RefusDepot, val noms: List<String> = emptyList()) : ExamenDepot
}

/** Vers le téléviseur, ou depuis lui vers l'ordinateur : un envoi et une copie se suivent de la même façon. */
enum class SensTransfert { ENVOI, RECEPTION }

/** Où en est un envoi : le fichier en cours et son rang, les octets partis — ou arrivés — sur l'ensemble. */
data class AvanceeDepot(
    val fichier: String,
    val rang: Int,
    val nombre: Int,
    val envoye: Long,
    val total: Long,
    val sens: SensTransfert = SensTransfert.ENVOI,
)

data class EchecDepot(val chemin: String, val motif: String)

data class ResultatDepot(
    val destination: String,
    val envoyes: Int,
    val nombre: Int,
    val echecs: List<EchecDepot> = emptyList(),
    /** Arrêté à la demande de la personne. */
    val annule: Boolean = false,
    /** Arrêté par la connexion : ce qui suivait n'est pas parti. */
    val interrompu: Boolean = false,
    /** Une copie vers l'ordinateur : [destination] est alors un dossier du disque, [envoyes] les fichiers copiés. */
    val sens: SensTransfert = SensTransfert.ENVOI,
) {
    val complet: Boolean get() = envoyes == nombre && echecs.isEmpty() && !annule && !interrompu
}
