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
class AdbKeyStore(
    private val context: Context,
    /** Tests must use a different name: they wipe the store. */
    private val vaultName: String = VAULT,
    /** Where older versions left the key in plain text. */
    private val oldFolder: File = File(context.filesDir, "adb"),
) {

    private val vault by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            vaultName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Returns the key pair, creating it on first use. */
    @Synchronized
    fun pair(): AdbKeyPair {
        recoverOldPlaintextKey()
        val der = read(PRIVATE_KEY)
        val publicKey = read(PUBLIC_KEY)
        if (der == null || publicKey == null) return create()
        return runCatching { assemble(der, publicKey) }.getOrElse { create() }
    }

    /**
     * Generates the pair, stores it encrypted, then deletes the plain files dadb wrote. This is the only moment
     * the key touches the disk unprotected.
     */
    private fun create(): AdbKeyPair {
        val folder = File(context.cacheDir, "cles-temporaires").apply { mkdirs() }
        val privateKeyFile = File(folder, "adbkey")
        val publicKeyFile = File(folder, "adbkey.pub")
        try {
            privateKeyFile.delete()
            publicKeyFile.delete()
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)

            val der = derFromPem(privateKeyFile.readText())
            val publicKey = publicKeyFile.readBytes()
            store(der, publicKey)
            return assemble(der, publicKey)
        } finally {
            privateKeyFile.delete()
            publicKeyFile.delete()
            folder.delete()
        }
    }

    /**
     * Migrates a plain-text key left by an older version, then deletes it. Generating a new key instead would
     * force re-authorizing every TV with the remote.
     */
    private fun recoverOldPlaintextKey() {
        val privateKey = File(oldFolder, "adbkey")
        val publicKey = File(oldFolder, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) return

        if (read(PRIVATE_KEY) == null) {
            val retry = runCatching {
                store(derFromPem(privateKey.readText()), publicKey.readBytes())
            }
            // Never delete a key that could not be stored: losing it means re-authorizing every TV.
            if (retry.getOrDefault(false) != true) return
        }
        privateKey.delete()
        publicKey.delete()
        oldFolder.delete()
    }

    /**
     * Synchronous `commit()`: `apply()` defers the disk write, and a process killed meanwhile would leave an
     * empty store after the plain key was deleted.
     */
    private fun store(der: ByteArray, publicKey: ByteArray): Boolean = vault.edit()
        .putString(PRIVATE_KEY, encoder(der))
        .putString(PUBLIC_KEY, encoder(publicKey))
        .commit()

    private fun assemble(der: ByteArray, publicKey: ByteArray): AdbKeyPair =
        AdbKeyPair(privateKeyFromDer(der), publicKey)

    private fun read(name: String): ByteArray? =
        vault.getString(name, null)?.let { Base64.getDecoder().decode(it) }

    private fun encoder(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val VAULT = "cles-adb"
        const val PRIVATE_KEY = "privee"
        const val PUBLIC_KEY = "publique"
    }
}
