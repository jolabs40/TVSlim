package net.jolabs40.tvslim.remote.adb

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dadb.AdbKeyPair
import java.io.File
import java.util.Base64

/**
 * Stores the app's ADB key pair.
 *
 * The private key grants full shell access to every TV that authorized it, so it is not kept in plain text in
 * `filesDir` (readable on a rooted device or through backups). It is stored encrypted, with the master key in
 * the Android keystore (hardware-backed when available).
 *
 * dadb signs with `RSA/ECB/NoPadding` and needs a real `PrivateKey`. dadb generates the pair once, the DER goes
 * into encrypted storage and the plain files are deleted; afterwards the key only exists in memory, rebuilt
 * through `AdbKeyPair(PrivateKey, ByteArray)`.
 */
class DepotCles(
    private val contexte: Context,
    /** Tests must use a different name: they wipe the store. */
    private val nomCoffre: String = COFFRE,
    /** Where older versions left the key in plain text. */
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

    /** Returns the key pair, creating it on first use. */
    @Synchronized
    fun paire(): AdbKeyPair {
        reprendreAncienneCleEnClair()
        val der = lire(CLE_PRIVEE)
        val publique = lire(CLE_PUBLIQUE)
        if (der == null || publique == null) return creer()
        return runCatching { assembler(der, publique) }.getOrElse { creer() }
    }

    /**
     * Generates the pair, stores it encrypted, then deletes the plain files dadb wrote. This is the only moment
     * the key touches the disk unprotected.
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
     * Migrates a plain-text key left by an older version, then deletes it. Generating a new key instead would
     * force re-authorizing every TV with the remote.
     */
    private fun reprendreAncienneCleEnClair() {
        val privee = File(ancienDossier, "adbkey")
        val publique = File(ancienDossier, "adbkey.pub")
        if (!privee.exists() || !publique.exists()) return

        if (lire(CLE_PRIVEE) == null) {
            val reprise = runCatching {
                ranger(derDepuisPem(privee.readText()), publique.readBytes())
            }
            // Never delete a key that could not be stored: losing it means re-authorizing every TV.
            if (reprise.getOrDefault(false) != true) return
        }
        privee.delete()
        publique.delete()
        ancienDossier.delete()
    }

    /**
     * Synchronous `commit()`: `apply()` defers the disk write, and a process killed meanwhile would leave an
     * empty store after the plain key was deleted.
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
