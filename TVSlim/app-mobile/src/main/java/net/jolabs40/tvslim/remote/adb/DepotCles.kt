package net.jolabs40.tvslim.remote.adb

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dadb.AdbKeyPair
import java.io.File
import java.util.Base64

/**
 * Garde la paire de clés ADB du compagnon.
 *
 * Cette clé privée vaut un accès shell complet à tous les téléviseurs qui l'ont autorisée : elle
 * mérite mieux qu'un fichier en clair dans `filesDir`, où le moindre appareil rooté ou une
 * sauvegarde trop bavarde la lirait. Elle est donc conservée **chiffrée**, la clé maître vivant
 * dans le keystore Android — matériel quand l'appareil en dispose.
 *
 * dadb signe en `RSA/ECB/NoPadding` et exige donc une vraie `PrivateKey`. On génère une fois par
 * ses soins, on range le DER au coffre, on efface les fichiers en clair : ensuite la clé n'existe
 * plus qu'en mémoire, reconstruite à la demande via `AdbKeyPair(PrivateKey, ByteArray)`.
 */
class DepotCles(
    private val contexte: Context,
    /** Nom du coffre. Un test doit en prendre un autre : il en efface le contenu. */
    private val nomCoffre: String = COFFRE,
    /** Emplacement d'une clé laissée en clair par une version précédente. */
    private val ancienDossier: File = File(contexte.filesDir, "adb"),
) {

    private val coffre by lazy {
        val cleMaitre = MasterKey.Builder(contexte)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            contexte,
            nomCoffre,
            cleMaitre,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** La paire du compagnon, créée à la première demande et gardée chiffrée ensuite. */
    @Synchronized
    fun paire(): AdbKeyPair {
        reprendreAncienneCleEnClair()
        val der = lire(CLE_PRIVEE)
        val publique = lire(CLE_PUBLIQUE)
        if (der == null || publique == null) return creer()
        return runCatching { assembler(der, publique) }.getOrElse { creer() }
    }

    /**
     * Fabrique la paire, la met au coffre, puis efface les fichiers que dadb a écrits en clair.
     * C'est le seul instant où le secret touche le disque sans protection.
     */
    private fun creer(): AdbKeyPair {
        val dossier = File(contexte.cacheDir, "cles-temporaires").apply { mkdirs() }
        val fichierPrive = File(dossier, "adbkey")
        val fichierPublic = File(dossier, "adbkey.pub")
        try {
            fichierPrive.delete()
            fichierPublic.delete()
            AdbKeyPair.generate(fichierPrive, fichierPublic)

            val der = derDepuisPem(fichierPrive.readText())
            val publique = fichierPublic.readBytes()
            ranger(der, publique)
            return assembler(der, publique)
        } finally {
            fichierPrive.delete()
            fichierPublic.delete()
            dossier.delete()
        }
    }

    /**
     * Reprend la clé laissée en clair par une version précédente, puis la supprime. Sans cela,
     * la mise à jour laisserait le secret exposé tout en croyant l'avoir protégé — et changer de
     * clé obligerait à réautoriser chaque téléviseur à la télécommande.
     */
    private fun reprendreAncienneCleEnClair() {
        val privee = File(ancienDossier, "adbkey")
        val publique = File(ancienDossier, "adbkey.pub")
        if (!privee.exists() || !publique.exists()) return

        if (lire(CLE_PRIVEE) == null) {
            val reprise = runCatching {
                ranger(derDepuisPem(privee.readText()), publique.readBytes())
            }
            // Effacer une clé qu'on n'a pas su ranger, ce serait la perdre — et obliger à
            // réautoriser le débogage à la télécommande sur chaque téléviseur. On la laisse.
            if (reprise.getOrDefault(false) != true) return
        }
        privee.delete()
        publique.delete()
        ancienDossier.delete()
    }

    /**
     * Écriture **synchrone** : `apply()` diffère l'écriture disque, et un processus tué entre
     * temps laisserait un coffre vide alors qu'on vient d'effacer la clé en clair.
     */
    private fun ranger(der: ByteArray, publique: ByteArray): Boolean = coffre.edit()
        .putString(CLE_PRIVEE, encoder(der))
        .putString(CLE_PUBLIQUE, encoder(publique))
        .commit()

    private fun assembler(der: ByteArray, publique: ByteArray): AdbKeyPair =
        AdbKeyPair(clePriveeDepuisDer(der), publique)

    private fun lire(nom: String): ByteArray? =
        coffre.getString(nom, null)?.let { Base64.getDecoder().decode(it) }

    private fun encoder(octets: ByteArray): String = Base64.getEncoder().encodeToString(octets)

    companion object {
        const val COFFRE = "cles-adb"
        const val CLE_PRIVEE = "privee"
        const val CLE_PUBLIQUE = "publique"
    }
}
