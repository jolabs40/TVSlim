package net.jolabs40.tvslim.installation

import java.nio.charset.Charset

/** Ce qu'un APK dit de lui-même, lu dans son manifeste. */
data class ManifesteApk(
    val paquet: String,
    val versionCode: Long,
    val versionName: String,
    /** `null` quand l'APK ne le déclare pas, ou le déclare par le nom de code d'une préversion. */
    val minSdk: Int?,
)

/** Un élément du XML binaire : son nom, et ses attributs rangés par nom. */
internal data class ElementBinaire(val nom: String, val attributs: Map<String, ValeurBinaire>)

/** La valeur d'un attribut : un texte, un entier, ou une référence à une ressource qu'on ne résout pas. */
internal data class ValeurBinaire(val texte: String?, val type: Int, val donnee: Int) {
    val entier: Int? get() = if (type in TYPE_ENTIER_DECIMAL..TYPE_ENTIER_HEXADECIMAL) donnee else null
}

/**
 * Lecteur du manifeste compilé d'un APK — le format « AXML » d'`aapt`, un XML binaire en petit-boutiste.
 *
 * Il ne sert qu'à dire, avant d'envoyer quoi que ce soit au téléviseur, quelle application on s'apprête
 * à installer : son paquet, sa version, l'Android qu'elle exige. Aucun outil du SDK n'est nécessaire, et
 * rien d'Android n'est appelé : le build Windows compile ce fichier tel quel.
 *
 * Trois morceaux comptent. La table des chaînes, en UTF-16 pour un manifeste — `aapt2` l'impose, pour les
 * anciens Android — et en UTF-8 pour les autres ressources. La table des identifiants, qui nomme un
 * attribut `android:` même quand un obfuscateur a vidé son nom. Et les débuts d'éléments, avec leurs
 * attributs de vingt octets chacun.
 */
internal object ManifesteBinaire {

    /** Le manifeste de ces octets, ou `null` s'ils n'en sont pas un — tronqués, ou fabriqués. */
    fun lire(octets: ByteArray): ManifesteApk? {
        val elements = runCatching { elements(octets) }.getOrNull() ?: return null
        val manifeste = elements.firstOrNull { it.nom == "manifest" } ?: return null
        val paquet = manifeste.attributs["package"]?.texte?.takeIf { it.isNotBlank() } ?: return null

        // Deux mots de 32 bits, lus sans signe : versionCodeMajor n'existe que depuis Android 9.
        val mineur = manifeste.attributs["versionCode"]?.entier?.toLong()?.and(MASQUE_32_BITS) ?: 0L
        val majeur = manifeste.attributs["versionCodeMajor"]?.entier?.toLong()?.and(MASQUE_32_BITS) ?: 0L
        return ManifesteApk(
            paquet = paquet,
            versionCode = (majeur shl 32) or mineur,
            versionName = manifeste.attributs["versionName"]?.texte.orEmpty(),
            minSdk = elements.firstOrNull { it.nom == "uses-sdk" }?.attributs?.get("minSdkVersion")?.entier,
        )
    }

    /** Tous les débuts d'éléments du document, dans l'ordre. Lève une exception sur un fichier malformé. */
    fun elements(octets: ByteArray): List<ElementBinaire> {
        val lecteur = Octets(octets)
        require(lecteur.u16(0) == TYPE_XML) { "pas un XML binaire" }

        var chaines = emptyList<String>()
        var identifiants = IntArray(0)
        val elements = mutableListOf<ElementBinaire>()
        var position = lecteur.u16(2)
        while (position + TAILLE_ENTETE <= octets.size) {
            val type = lecteur.u16(position)
            val entete = lecteur.u16(position + 2)
            val taille = lecteur.i32(position + 4)
            // Une taille nulle ferait tourner la boucle sur place ; une taille trop grande, lire ailleurs.
            require(taille >= TAILLE_ENTETE && position.toLong() + taille <= octets.size) { "morceau malformé" }
            when (type) {
                TYPE_CHAINES -> chaines = lireChaines(lecteur, position, entete, taille)
                TYPE_IDENTIFIANTS -> identifiants = IntArray((taille - entete) / 4) { lecteur.i32(position + entete + it * 4) }
                TYPE_DEBUT_ELEMENT -> elements += lireElement(lecteur, position + entete, chaines, identifiants)
            }
            position += taille
        }
        return elements
    }

    private fun lireChaines(lecteur: Octets, position: Int, entete: Int, taille: Int): List<String> {
        val nombre = lecteur.i32(position + 8)
        require(nombre >= 0 && entete + nombre.toLong() * 4 <= taille) { "table des chaînes malformée" }
        val utf8 = lecteur.i32(position + 16) and DRAPEAU_UTF8 != 0
        val debut = position + lecteur.i32(position + 20)
        return List(nombre) { rang ->
            val decalage = debut + lecteur.i32(position + entete + rang * 4)
            if (utf8) chaineUtf8(lecteur, decalage) else chaineUtf16(lecteur, decalage)
        }
    }

    /** Deux longueurs précèdent le texte : en caractères UTF-16, inutile ici, puis en octets. */
    private fun chaineUtf8(lecteur: Octets, debut: Int): String {
        var position = debut + if (lecteur.u8(debut) and 0x80 != 0) 2 else 1
        var longueur = lecteur.u8(position++)
        if (longueur and 0x80 != 0) longueur = ((longueur and 0x7F) shl 8) or lecteur.u8(position++)
        return lecteur.texte(position, longueur, Charsets.UTF_8)
    }

    private fun chaineUtf16(lecteur: Octets, debut: Int): String {
        var position = debut + 2
        var longueur = lecteur.u16(debut)
        if (longueur and 0x8000 != 0) {
            longueur = ((longueur and 0x7FFF) shl 16) or lecteur.u16(position)
            position += 2
        }
        return lecteur.texte(position, longueur * 2, Charsets.UTF_16LE)
    }

    private fun lireElement(
        lecteur: Octets,
        extension: Int,
        chaines: List<String>,
        identifiants: IntArray,
    ): ElementBinaire {
        val debut = lecteur.u16(extension + 8)
        val largeur = lecteur.u16(extension + 10)
        val nombre = lecteur.u16(extension + 12)
        val attributs = (0 until nombre).associate { rang ->
            val attribut = extension + debut + rang * largeur
            val indexNom = lecteur.i32(attribut + 4)
            val brut = lecteur.i32(attribut + 8)
            val type = lecteur.u8(attribut + 15)
            val donnee = lecteur.i32(attribut + 16)
            // L'identifiant d'abord : il résiste à un nom d'attribut vidé par un obfuscateur.
            val nom = identifiants.getOrNull(indexNom)?.let(NOMS_PAR_IDENTIFIANT::get)
                ?: chaines.getOrElse(indexNom) { "" }
            val texte = when {
                brut >= 0 -> chaines.getOrNull(brut)
                type == TYPE_CHAINE -> chaines.getOrNull(donnee)
                else -> null
            }
            nom to ValeurBinaire(texte, type, donnee)
        }
        return ElementBinaire(chaines.getOrElse(lecteur.i32(extension + 4)) { "" }, attributs)
    }

    /** Lecture en petit-boutiste. Un décalage hors du tableau lève une exception, que [lire] rattrape. */
    private class Octets(private val octets: ByteArray) {
        fun u8(position: Int): Int = octets[position].toInt() and 0xFF
        fun u16(position: Int): Int = u8(position) or (u8(position + 1) shl 8)
        fun i32(position: Int): Int = u16(position) or (u16(position + 2) shl 16)
        fun texte(position: Int, longueur: Int, jeu: Charset): String = String(octets, position, longueur, jeu)
    }

    private const val TAILLE_ENTETE = 8
    private const val TYPE_XML = 0x0003
    private const val TYPE_CHAINES = 0x0001
    private const val TYPE_IDENTIFIANTS = 0x0180
    private const val TYPE_DEBUT_ELEMENT = 0x0102
    private const val DRAPEAU_UTF8 = 1 shl 8
    private const val TYPE_CHAINE = 0x03
    private const val MASQUE_32_BITS = 0xFFFFFFFFL

    /** Les attributs `android:` qui nous intéressent, par identifiant de ressource du cadre. */
    private val NOMS_PAR_IDENTIFIANT = mapOf(
        0x0101021b to "versionCode",
        0x0101021c to "versionName",
        0x0101020c to "minSdkVersion",
        0x01010576 to "versionCodeMajor",
    )
}

private const val TYPE_ENTIER_DECIMAL = 0x10
private const val TYPE_ENTIER_HEXADECIMAL = 0x11
