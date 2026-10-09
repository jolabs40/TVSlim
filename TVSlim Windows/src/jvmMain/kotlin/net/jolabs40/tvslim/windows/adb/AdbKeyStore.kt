package net.jolabs40.tvslim.windows.adb

import dadb.AdbKeyPair
import net.jolabs40.tvslim.windows.tools.AppLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Stores the app's ADB key pair.
 *
 * The private key grants full shell access to every TV that authorized it, so it is stored encrypted with
 * DPAPI ([DpapiProtection]), never in plain text (the phone uses the Android keystore).
 *
 * dadb signs with `RSA/ECB/NoPadding` and needs a real `PrivateKey`: the pair is generated once by dadb, its DER
 * is encrypted and the plain-text files are deleted. After that the key only exists in memory, rebuilt with
 * `AdbKeyPair(PrivateKey, ByteArray)`.
 */
class AdbKeyStore(
    private val folder: File,
    private val protection: DataProtection = DpapiProtection,
) {

    private val privateKeyFile = File(folder, PRIVATE_KEY_FILE)
    private val publicKeyFile = File(folder, PUBLIC_KEY_FILE)

    @Volatile
    private var inMemory: AdbKeyPair? = null

    /** Returns the key pair, creating and storing it on first use. */
    @Synchronized
    fun pair(): AdbKeyPair {
        inMemory?.let { return it }
        val pair = read() ?: create()
        inMemory = pair
        return pair
    }

    private fun read(): AdbKeyPair? {
        if (!privateKeyFile.exists() || !publicKeyFile.exists()) return null
        return runCatching {
            val der = protection.unprotect(privateKeyFile.readBytes())
            AdbKeyPair(privateKeyFromDer(der), publicKeyFile.readBytes())
        }.getOrElse { error ->
            // An unreadable key (profile restored on another machine, corrupt file) is set aside, not deleted.
            // A new one replaces it and each TV will ask for authorization again.
            AppLog.warn(TAG, "ADB key unreadable, a new one will replace it", error)
            val suffix = ".illisible-${System.currentTimeMillis()}"
            privateKeyFile.renameTo(File(folder, PRIVATE_KEY_FILE + suffix))
            publicKeyFile.renameTo(File(folder, PUBLIC_KEY_FILE + suffix))
            null
        }
    }

    /**
     * Generates the pair, encrypts it, then deletes the plain-text files dadb wrote. This is the only moment the
     * secret is on disk unprotected.
     */
    private fun create(): AdbKeyPair {
        folder.mkdirs()
        val tempDir = Files.createTempDirectory(folder.toPath(), "generation").toFile()
        val privateKey = File(tempDir, "adbkey")
        val publicKey = File(tempDir, "adbkey.pub")
        try {
            AdbKeyPair.generate(privateKey, publicKey)
            val der = derFromPem(privateKey.readText())
            val publicBytes = publicKey.readBytes()
            // Private key first: a half-written pair without its public file is ignored on the next read and
            // regenerated.
            writeAtomically(privateKeyFile, protection.protect(der))
            writeAtomically(publicKeyFile, publicBytes)
            return AdbKeyPair(privateKeyFromDer(der), publicBytes)
        } finally {
            privateKey.delete()
            publicKey.delete()
            tempDir.delete()
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeBytes(bytes)
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private companion object {
        const val TAG = "Keys"
        const val PRIVATE_KEY_FILE = "adbkey.dpapi"
        const val PUBLIC_KEY_FILE = "adbkey.pub"
    }
}
