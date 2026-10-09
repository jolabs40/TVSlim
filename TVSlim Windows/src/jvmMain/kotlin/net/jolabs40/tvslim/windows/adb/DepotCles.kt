package net.jolabs40.tvslim.windows.adb

import dadb.AdbKeyPair
import net.jolabs40.tvslim.windows.outils.Traces
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Stores the app's ADB key pair.
 *
 * The private key grants full shell access to every TV that authorized it, so it is stored encrypted with
 * DPAPI ([ProtectionDpapi]), never in plain text (the phone uses the Android keystore).
 *
 * dadb signs with `RSA/ECB/NoPadding` and needs a real `PrivateKey`: the pair is generated once by dadb, its DER
 * is encrypted and the plain-text files are deleted. After that the key only exists in memory, rebuilt with
 * `AdbKeyPair(PrivateKey, ByteArray)`.
 */
class DepotCles(
    private val dossier: File,
    private val protection: ProtectionDonnees = ProtectionDpapi,
) {

    private val fichierPrive = File(dossier, FICHIER_PRIVE)
    private val fichierPublic = File(dossier, FICHIER_PUBLIC)

    @Volatile
    private var enMemoire: AdbKeyPair? = null

    /** Returns the key pair, creating and storing it on first use. */
    @Synchronized
    fun paire(): AdbKeyPair {
        enMemoire?.let { return it }
        val paire = lire() ?: creer()
        enMemoire = paire
        return paire
    }

    private fun lire(): AdbKeyPair? {
        if (!fichierPrive.exists() || !fichierPublic.exists()) return null
        return runCatching {
            val der = protection.lever(fichierPrive.readBytes())
            AdbKeyPair(clePriveeDepuisDer(der), fichierPublic.readBytes())
        }.getOrElse { erreur ->
            // An unreadable key (profile restored on another machine, corrupt file) is set aside, not deleted.
            // A new one replaces it and each TV will ask for authorization again.
            Traces.avertir(TAG, "Clé ADB illisible, une nouvelle va la remplacer", erreur)
            val suffixe = ".illisible-${System.currentTimeMillis()}"
            fichierPrive.renameTo(File(dossier, FICHIER_PRIVE + suffixe))
            fichierPublic.renameTo(File(dossier, FICHIER_PUBLIC + suffixe))
            null
        }
    }

    /**
     * Generates the pair, encrypts it, then deletes the plain-text files dadb wrote. This is the only moment the
     * secret is on disk unprotected.
     */
    private fun creer(): AdbKeyPair {
        dossier.mkdirs()
        val temporaire = Files.createTempDirectory(dossier.toPath(), "generation").toFile()
        val prive = File(temporaire, "adbkey")
        val publique = File(temporaire, "adbkey.pub")
        try {
            AdbKeyPair.generate(prive, publique)
            val der = derDepuisPem(prive.readText())
            val octetsPublics = publique.readBytes()
            // Private key first: a half-written pair without its public file is ignored on the next read and
            // regenerated.
            ecrireAtomiquement(fichierPrive, protection.proteger(der))
            ecrireAtomiquement(fichierPublic, octetsPublics)
            return AdbKeyPair(clePriveeDepuisDer(der), octetsPublics)
        } finally {
            prive.delete()
            publique.delete()
            temporaire.delete()
        }
    }

    private fun ecrireAtomiquement(cible: File, octets: ByteArray) {
        val provisoire = File(cible.parentFile, cible.name + ".tmp")
        provisoire.writeBytes(octets)
        Files.move(
            provisoire.toPath(),
            cible.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private companion object {
        const val TAG = "Cles"
        const val FICHIER_PRIVE = "adbkey.dpapi"
        const val FICHIER_PUBLIC = "adbkey.pub"
    }
}
