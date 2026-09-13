package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet

/** D'où vient un paquet : la plateforme Android, le constructeur de l'appareil, ou quelqu'un d'autre. */
enum class OriginePaquet {
    ANDROID,
    CONSTRUCTEUR,
    AUTRE,
    ;

    companion object {
        /** L'ordre de lecture : le constructeur d'abord, c'est là que se cache ce qu'on vient chercher. */
        val ORDRE = listOf(CONSTRUCTEUR, ANDROID, AUTRE)

        /**
         * L'origine d'un paquet que le catalogue ne décrit pas, devinée d'après son nom :
         *
         *  - les fondeurs de puces (MediaTek, Realtek, Amlogic…) : constructeur, quelle que soit la marque ;
         *  - `android.`, `com.android.`, `com.google.` : Android ;
         *  - la marque de l'appareil dans un segment du nom (« com.tcl.tv ») ou l'un de ses préfixes
         *    connus (`org.droidtv` chez Philips) : constructeur ;
         *  - le reste — partenaires préinstallés, opérateurs : autre.
         *
         * Une supposition : elle range et elle montre, elle ne décide jamais de rien.
         */
        fun de(paquet: String, fabricant: Fabricant?): OriginePaquet {
            val nom = paquet.lowercase()
            return when {
                PREFIXES_FONDEURS.any { nom.startsWith(it) } -> CONSTRUCTEUR
                nom == "android" || PREFIXES_ANDROID.any { nom.startsWith(it) } -> ANDROID
                fabricant != null && fabricant.signePaquet(nom) -> CONSTRUCTEUR
                PREFIXES_MARQUES[fabricant].orEmpty().any { nom.startsWith(it) } -> CONSTRUCTEUR
                else -> AUTRE
            }
        }

        private val PREFIXES_ANDROID = listOf("android.", "com.android.", "com.google.")

        private val PREFIXES_FONDEURS = listOf(
            "com.mediatek.", "com.mstar.", "com.realtek.", "com.amlogic.", "com.droidlogic.",
            "com.hisilicon.", "com.rockchip.", "com.allwinner.", "com.broadcom.",
        )

        /** Les éditeurs dont le nom ne porte pas la marque : filiales, sous-traitants, marques sœurs. */
        private val PREFIXES_MARQUES = mapOf(
            Fabricant.PHILIPS to listOf("org.droidtv."),
            Fabricant.HISENSE to listOf("com.jamdeo."),
            Fabricant.XIAOMI to listOf("com.mitv.", "com.miui.", "com.duokan."),
            // Les téléviseurs Thomson sous Android sont fabriqués par TCL.
            Fabricant.THOMSON to listOf("com.tcl."),
        )
    }
}

/**
 * L'origine d'une entrée du catalogue, d'après la marque qu'il lui donne ; à défaut, devinée d'après
 * son nom comme pour un paquet inconnu.
 */
val EntreePaquet.origine: OriginePaquet
    get() = when (marque.trim().lowercase()) {
        "google", "aosp", "android" -> OriginePaquet.ANDROID
        "third party" -> OriginePaquet.AUTRE
        "" -> OriginePaquet.de(paquet, null)
        else -> OriginePaquet.CONSTRUCTEUR
    }

/** Un paquet livré avec l'appareil que le catalogue ne décrit pas encore. */
data class PaquetInconnu(
    val paquet: String,
    val etat: EtatPaquet,
    val origine: OriginePaquet,
) {
    /**
     * « org.droidtv », « com.mediatek » : ce qui rassemble les paquets d'un même éditeur. Le cadre
     * lui-même, `android`, n'a pas de domaine : ses surcouches (« android.auto_generated_rro_vendor__ »)
     * se rangent avec lui au lieu d'ouvrir chacune sa famille — relevé sur la TCL, quatre tableaux d'une ligne.
     */
    val famille: String
        get() = paquet.split('.').let { segments ->
            if (segments.first() == "android") "android" else segments.take(2).joinToString(".")
        }
}

/**
 * Les paquets livrés avec l'appareil que le catalogue ignore : ni entrée, ni liste noire, ni launcher
 * connu. Rangés par origine, puis par nom. On les montre sans rien proposer d'en faire : un paquet
 * système inconnu peut porter le tuner, les entrées ou la télécommande. Leur inventaire, avec ce
 * qu'ADB en dit, s'exporte par [RapportInconnus].
 */
fun Catalogue.paquetsInconnus(systeme: Map<String, EtatPaquet>, fabricant: Fabricant?): List<PaquetInconnu> {
    val connus = buildSet {
        entrees.mapTo(this) { it.paquet }
        proteges.mapTo(this) { it.paquet }
        launchers.forEach { add(it.paquet); addAll(it.variantes) }
        launchersConnus.forEach { addAll(it.paquets) }
    }
    return systeme
        .filterKeys { it !in connus }
        .map { (paquet, etat) -> PaquetInconnu(paquet, etat, OriginePaquet.de(paquet, fabricant)) }
        .sortedWith(compareBy<PaquetInconnu> { OriginePaquet.ORDRE.indexOf(it.origine) }.thenBy { it.paquet })
}
