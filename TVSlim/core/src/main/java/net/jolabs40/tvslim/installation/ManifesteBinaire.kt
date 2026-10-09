package net.jolabs40.tvslim.installation

import java.nio.charset.Charset

/** What an APK declares about itself in its manifest. */
data class ManifesteApk(
    val paquet: String,
    val versionCode: Long,
    val versionName: String,
    /** `null` when not declared, or declared as a preview codename. */
    val minSdk: Int?,
)

/** A binary XML element: its name and its attributes by name. */
internal data class ElementBinaire(val nom: String, val attributs: Map<String, ValeurBinaire>)

/** Attribute value: a string, an integer, or a resource reference that is not resolved. */
internal data class ValeurBinaire(val texte: String?, val type: Int, val donnee: Int) {
    val entier: Int? get() = if (type in TYPE_ENTIER_DECIMAL..TYPE_ENTIER_HEXADECIMAL) donnee else null
}

/**
 * Reads an APK's compiled manifest (`aapt`'s AXML, little-endian binary XML) to show package, version
 * and minimum Android before anything is sent to the TV. Uses no SDK tool or Android API, so the
 * Windows build compiles it as is.
 *
 * Three chunk types matter: the string pool (UTF-16 in a manifest, as `aapt2` enforces for old Android
 * versions; UTF-8 elsewhere), the resource ID map (names an `android:` attribute even when an
 * obfuscator blanked it), and start elements with their 20-byte attributes.
 */
internal object ManifesteBinaire {

    /** Parses [octets], or returns `null` if they are not a manifest (truncated or crafted). */
    fun lire(octets: ByteArray): ManifesteApk? {
        val elements = runCatching { elements(octets) }.getOrNull() ?: return null
        val manifeste = elements.firstOrNull { it.nom == "manifest" } ?: return null
        val paquet = manifeste.attributs["package"]?.texte?.takeIf { it.isNotBlank() } ?: return null

        // Two unsigned 32-bit words; versionCodeMajor only exists since Android 9.
        val mineur = manifeste.attributs["versionCode"]?.entier?.toLong()?.and(MASQUE_32_BITS) ?: 0L
        val majeur = manifeste.attributs["versionCodeMajor"]?.entier?.toLong()?.and(MASQUE_32_BITS) ?: 0L
        return ManifesteApk(
            paquet = paquet,
            versionCode = (majeur shl 32) or mineur,
            versionName = manifeste.attributs["versionName"]?.texte.orEmpty(),
            minSdk = elements.firstOrNull { it.nom == "uses-sdk" }?.attributs?.get("minSdkVersion")?.entier,
        )
    }

    /** Returns all start elements of the document, in order. Throws on a malformed file. */
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
            // A zero size would loop forever; an oversized one would read out of bounds.
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

    /** Two lengths precede the text: in UTF-16 characters (unused here), then in bytes. */
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
            // Resource ID first: it survives an attribute name blanked by an obfuscator.
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

    /** Little-endian reads. An out-of-range offset throws, and [lire] catches it. */
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

    /** The `android:` attributes read here, by framework resource ID. */
    private val NOMS_PAR_IDENTIFIANT = mapOf(
        0x0101021b to "versionCode",
        0x0101021c to "versionName",
        0x0101020c to "minSdkVersion",
        0x01010576 to "versionCodeMajor",
    )
}

private const val TYPE_ENTIER_DECIMAL = 0x10
private const val TYPE_ENTIER_HEXADECIMAL = 0x11
