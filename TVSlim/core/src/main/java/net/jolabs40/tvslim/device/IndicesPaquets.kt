package net.jolabs40.tvslim.device

/**
 * What an app declares to the system that makes disabling it risky, most serious first. Each is read with
 * an intent query that answers for all packages at once.
 */
enum class DeclarationSensible {
    /** TV input service: tuner, HDMI inputs, a partner's channels. */
    ENTREE_TV,

    /** Accessibility service: screen reader, hearing aid... */
    ACCESSIBILITE,

    /** Input method: disabling it can leave the TV without any keyboard. */
    CLAVIER,

    /** Boot receiver: the app starts with the device. */
    DEMARRAGE,
}

/** What ADB reports about a package, to judge whether it is safe to touch. Read by [LectureIndices]. */
data class IndicesPaquet(
    /** The factory APK, on the system partition even when an update overrides it. */
    val chemin: String = "",
    /** A newer version is installed over the factory one, in /data/app. */
    val misAJour: Boolean = false,
    /** Linux UID the app runs under; null when Android did not report it. */
    val uid: Int? = null,
    val declarations: Set<DeclarationSensible> = emptySet(),
    /** Has an entry in the app menu, Android TV's or the classic one. */
    val icone: Boolean = false,
) {
    /** Partition and folder (`system_ext/app`, `product/priv-app`, `data/app`) rather than the full path. */
    val emplacement: String
        get() {
            val dossiers = chemin.split('/').filter { it.isNotEmpty() }.dropLast(1)
            val rang = dossiers.indexOfFirst { it in DOSSIERS_D_APPLICATIONS }
            return (if (rang >= 0) dossiers.take(rang + 1) else dossiers.take(2)).joinToString("/")
        }

    /** Installed in a `priv-app` folder: Android grants it permissions it denies to other apps. */
    val privilegiee: Boolean get() = "/priv-app/" in chemin

    /** Runs as the system user (UID 1000): whatever it does, the system does. */
    val droitsSysteme: Boolean get() = uid == UID_SYSTEME

    /** A UID reserved for the platform (system, telephony, Bluetooth, NFC) rather than for an app. */
    val uidReserve: Boolean get() = uid != null && uid < PREMIER_UID_APPLICATION

    companion object {
        const val UID_SYSTEME = 1000
        const val PREMIER_UID_APPLICATION = 10_000

        private val DOSSIERS_D_APPLICATIONS = setOf("app", "priv-app", "overlay", "framework")
    }
}

/**
 * Reads the indices of every package in one read-only command: paths and UIDs, factory paths, one intent
 * query per sensitive declaration, then menu icons. Under a second for 77 KB of output on the TCL.
 */
object LectureIndices {

    const val MARQUEUR_FICHIERS = "@@TVSLIM_FICHIERS"

    /** Factory version of an updated app; without it only the /data/app copy would show. */
    const val MARQUEUR_USINE = "@@TVSLIM_USINE"
    const val MARQUEUR_ICONES = "@@TVSLIM_ICONES"

    fun marqueur(declaration: DeclarationSensible): String = "@@TVSLIM_${declaration.name}"

    /** `MATCH_DISABLED_COMPONENTS`: a disabled package still declares what it would do once re-enabled. */
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

    /** `package:/system_ext/app/TGuard/TGuard.apk=com.tcl.guard uid:1000`, the uid only with `-U`. */
    private fun fichiers(lignes: List<String>?): Map<String, Fichier> =
        lignes.orEmpty()
            .mapNotNull { LIGNE_FICHIER.matchEntire(it) }
            .associate { it.groupValues[2] to Fichier(it.groupValues[1], it.groupValues[3].toIntOrNull()) }

    /** Packages named by the components of a `--brief` output, like `com.tcl.tvinput/.TunerInputService`. */
    private fun paquetsDesComposants(lignes: List<String>?): Set<String> =
        lignes.orEmpty().mapNotNull { COMPOSANT.matchEntire(it)?.groupValues?.get(1) }.toSet()

    private fun services(action: String) =
        "cmd package query-services --brief --query-flags $AVEC_DESACTIVES -a $action"

    private fun recepteurs(action: String) =
        "cmd package query-receivers --brief --query-flags $AVEC_DESACTIVES -a $action"

    private fun menu(categorie: String) =
        "cmd package query-activities --brief --query-flags $AVEC_DESACTIVES -a android.intent.action.MAIN -c $categorie"

    /**
     * The path runs up to the last `=`: /data/app paths contain some (`~~taEQ...Xw==/`), package names never
     * do. One UID per user (`uid:10118,1010118`); the first is enough.
     */
    private val LIGNE_FICHIER = Regex("""package:(.+)=([A-Za-z0-9_.]+)(?:\s+uid:(\d+)\S*)?""")

    private val COMPOSANT = Regex("""([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+""")
}
