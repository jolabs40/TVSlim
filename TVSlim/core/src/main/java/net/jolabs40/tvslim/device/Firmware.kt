package net.jolabs40.tvslim.device

/**
 * Ce qui identifie un firmware plutôt qu'un appareil : deux téléviseurs du même modèle, vendus dans la
 * même région, portent les mêmes valeurs. Rien de la personne n'y figure — ni la langue qu'elle a
 * choisie, ni un numéro de série.
 */
data class Firmware(
    /** `ro.build.fingerprint` : marque, produit, appareil, Android et build — de quoi reconnaître un doublon. */
    val empreinte: String = "",
    /** `ro.product.name` : le produit, qui porte souvent la région (« G08_4K_GB » sur la TCL). */
    val produit: String = "",
    /** `ro.product.locale` : la langue d'usine, et non celle qu'a choisie la personne. */
    val langueUsine: String = "",
) {
    val renseigne: Boolean get() = empreinte.isNotEmpty() || produit.isNotEmpty() || langueUsine.isNotEmpty()
}

/**
 * Relève le [Firmware] en une commande, pour l'inventaire des inconnus : les paquets préinstallés changent
 * d'une région à l'autre, et d'une version du firmware à la suivante.
 */
object LectureFirmware {

    const val MARQUEUR_EMPREINTE = "@@TVSLIM_EMPREINTE"
    const val MARQUEUR_PRODUIT = "@@TVSLIM_PRODUIT"
    const val MARQUEUR_LANGUE = "@@TVSLIM_LANGUE"

    /** Une section par propriété : une valeur vide n'y laisse qu'une section vide, sans décaler les autres. */
    val COMMANDE: String = listOf(
        "echo $MARQUEUR_EMPREINTE",
        "getprop ro.build.fingerprint",
        "echo $MARQUEUR_PRODUIT",
        "getprop ro.product.name",
        "echo $MARQUEUR_LANGUE",
        "getprop ro.product.locale",
    ).joinToString("; ")

    fun interpreter(sortie: String): Firmware {
        val sections = LecteurDistant.decouper(sortie)
        fun valeur(marqueur: String) = sections[marqueur].orEmpty().firstOrNull().orEmpty()
        return Firmware(
            empreinte = valeur(MARQUEUR_EMPREINTE),
            produit = valeur(MARQUEUR_PRODUIT),
            langueUsine = valeur(MARQUEUR_LANGUE),
        )
    }
}
