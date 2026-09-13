package net.jolabs40.tvslim.device

/**
 * Ce qu'une application déclare au système et qui rend sa désactivation risquée, du plus grave au
 * moins grave. Chacune se lit par une requête d'intention, qui répond pour tous les paquets à la fois.
 */
enum class DeclarationSensible {
    /** Un service d'entrée TV : le tuner, les prises HDMI, les chaînes d'un partenaire. */
    ENTREE_TV,

    /** Un service d'accessibilité : lecteur d'écran, aide auditive… */
    ACCESSIBILITE,

    /** Une méthode de saisie : la couper peut priver le téléviseur de tout clavier. */
    CLAVIER,

    /** Un récepteur du démarrage : l'application se lance avec l'appareil. */
    DEMARRAGE,
}

/**
 * Ce qu'ADB dit d'un paquet, pour juger s'il est prudent d'y toucher avant de le décrire au catalogue.
 * Tout se relève d'un coup, en lecture seule, par [LectureIndices].
 */
data class IndicesPaquet(
    /** Le fichier d'usine de l'application : celui du système, même quand une mise à jour le recouvre. */
    val chemin: String = "",
    /** Une version plus récente installée par-dessus celle d'usine, dans /data/app. */
    val misAJour: Boolean = false,
    /** L'identifiant Linux sous lequel elle tourne ; null quand Android ne l'a pas donné. */
    val uid: Int? = null,
    val declarations: Set<DeclarationSensible> = emptySet(),
    /** Une entrée dans le menu des applications, celui d'Android TV ou le classique. */
    val icone: Boolean = false,
) {
    /**
     * La partition et le dossier — « system_ext/app », « product/priv-app », « data/app » — : d'où vient
     * l'application, sans la longueur du chemin entier.
     */
    val emplacement: String
        get() {
            val dossiers = chemin.split('/').filter { it.isNotEmpty() }.dropLast(1)
            val rang = dossiers.indexOfFirst { it in DOSSIERS_D_APPLICATIONS }
            return (if (rang >= 0) dossiers.take(rang + 1) else dossiers.take(2)).joinToString("/")
        }

    /** Installée dans un `priv-app` : Android lui accorde des permissions qu'il refuse aux autres. */
    val privilegiee: Boolean get() = "/priv-app/" in chemin

    /** Tourne sous l'identité du système (UID 1000) : ce qu'elle fait, le système le fait. */
    val droitsSysteme: Boolean get() = uid == UID_SYSTEME

    /** Un identifiant réservé à la plateforme — système, téléphonie, Bluetooth, NFC — plutôt qu'à une application. */
    val uidReserve: Boolean get() = uid != null && uid < PREMIER_UID_APPLICATION

    companion object {
        const val UID_SYSTEME = 1000
        const val PREMIER_UID_APPLICATION = 10_000

        private val DOSSIERS_D_APPLICATIONS = setOf("app", "priv-app", "overlay", "framework")
    }
}

/**
 * Relève les indices de tous les paquets en une seule commande, comme la photographie : chemins et
 * identifiants, chemins d'usine, une requête d'intention par déclaration sensible, puis les icônes du
 * menu. Rien que des lectures — sur la TCL, moins d'une seconde pour 77 Ko de sortie (2026-09-13).
 */
object LectureIndices {

    const val MARQUEUR_FICHIERS = "@@TVSLIM_FICHIERS"

    /** La version d'usine d'une application mise à jour : sans elle, on ne verrait que /data/app. */
    const val MARQUEUR_USINE = "@@TVSLIM_USINE"
    const val MARQUEUR_ICONES = "@@TVSLIM_ICONES"

    fun marqueur(declaration: DeclarationSensible): String = "@@TVSLIM_${declaration.name}"

    /** `MATCH_DISABLED_COMPONENTS` : un paquet désactivé déclare toujours ce qu'il ferait une fois réactivé. */
    private const val AVEC_DESACTIVES = 0x200

    private val REQUETES: Map<DeclarationSensible, List<String>> = mapOf(
        DeclarationSensible.ENTREE_TV to listOf(services("android.media.tv.TvInputService")),
        DeclarationSensible.ACCESSIBILITE to listOf(services("android.accessibilityservice.AccessibilityService")),
        DeclarationSensible.CLAVIER to listOf(services("android.view.InputMethod")),
        DeclarationSensible.DEMARRAGE to listOf(
            recepteurs("android.intent.action.BOOT_COMPLETED"),
            recepteurs("android.intent.action.LOCKED_BOOT_COMPLETED"),
        ),
    )

    val COMMANDE: String = buildList {
        add("echo $MARQUEUR_FICHIERS")
        add("pm list packages -f -U")
        add("echo $MARQUEUR_USINE")
        add("pm list packages -f --factory-only")
        REQUETES.forEach { (declaration, requetes) ->
            add("echo ${marqueur(declaration)}")
            addAll(requetes)
        }
        add("echo $MARQUEUR_ICONES")
        add(menu("android.intent.category.LEANBACK_LAUNCHER"))
        add(menu("android.intent.category.LAUNCHER"))
    }.joinToString("; ")

    fun interpreter(sortie: String): Map<String, IndicesPaquet> {
        val sections = LecteurDistant.decouper(sortie)
        val enPlace = fichiers(sections[MARQUEUR_FICHIERS])
        val usine = fichiers(sections[MARQUEUR_USINE])
        val declarants = DeclarationSensible.entries.associateWith { paquetsDesComposants(sections[marqueur(it)]) }
        val icones = paquetsDesComposants(sections[MARQUEUR_ICONES])

        return (enPlace.keys + usine.keys).associateWith { paquet ->
            val courant = enPlace[paquet]
            val dUsine = usine[paquet]
            IndicesPaquet(
                chemin = dUsine?.chemin ?: courant?.chemin.orEmpty(),
                misAJour = dUsine != null && courant != null && courant.chemin != dUsine.chemin,
                uid = courant?.uid,
                declarations = DeclarationSensible.entries.filterTo(mutableSetOf()) { paquet in declarants.getValue(it) },
                icone = paquet in icones,
            )
        }
    }

    private data class Fichier(val chemin: String, val uid: Int?)

    /** « package:/system_ext/app/TGuard/TGuard.apk=com.tcl.guard uid:1000 », l'uid seulement avec `-U`. */
    private fun fichiers(lignes: List<String>?): Map<String, Fichier> =
        lignes.orEmpty()
            .mapNotNull { LIGNE_FICHIER.matchEntire(it) }
            .associate { it.groupValues[2] to Fichier(it.groupValues[1], it.groupValues[3].toIntOrNull()) }

    /** Les paquets nommés par les composants d'une sortie `--brief` : « com.tcl.tvinput/.TunerInputService ». */
    private fun paquetsDesComposants(lignes: List<String>?): Set<String> =
        lignes.orEmpty().mapNotNull { COMPOSANT.matchEntire(it)?.groupValues?.get(1) }.toSet()

    private fun services(action: String) =
        "cmd package query-services --brief --query-flags $AVEC_DESACTIVES -a $action"

    private fun recepteurs(action: String) =
        "cmd package query-receivers --brief --query-flags $AVEC_DESACTIVES -a $action"

    private fun menu(categorie: String) =
        "cmd package query-activities --brief --query-flags $AVEC_DESACTIVES -a android.intent.action.MAIN -c $categorie"

    /**
     * Le chemin court jusqu'au **dernier** « = » : ceux de /data/app en portent (« ~~taEQ…Xw==/ »), un nom
     * de paquet jamais. Un identifiant par utilisateur (« uid:10118,1010118 ») : le premier suffit.
     */
    private val LIGNE_FICHIER = Regex("""package:(.+)=([A-Za-z0-9_.]+)(?:\s+uid:(\d+)\S*)?""")

    private val COMPOSANT = Regex("""([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+""")
}
