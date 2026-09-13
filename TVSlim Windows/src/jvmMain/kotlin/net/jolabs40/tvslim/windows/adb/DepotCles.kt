package net.jolabs40.tvslim.windows.adb

import dadb.AdbKeyPair
import net.jolabs40.tvslim.windows.outils.Traces
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Garde la paire de clés ADB de l'application.
 *
 * La clé privée vaut un accès shell complet à chaque téléviseur qui l'a autorisée : elle dort
 * chiffrée par DPAPI ([ProtectionDpapi]), jamais en clair — le même principe que sur le
 * téléphone, où c'est le keystore Android qui la garde.
 *
 * dadb signe en `RSA/ECB/NoPadding` et exige une vraie `PrivateKey` : on génère une fois par ses
 * soins, on chiffre le DER, on efface ce qu'il a écrit en clair. Ensuite la clé n'existe plus
 * qu'en mémoire, reconstruite via `AdbKeyPair(PrivateKey, ByteArray)`.
 */
class DepotCles(
    private val dossier: File,
    private val protection: ProtectionDonnees = ProtectionDpapi,
) {

    private val fichierPrive = File(dossier, FICHIER_PRIVE)
    private val fichierPublic = File(dossier, FICHIER_PUBLIC)

    @Volatile
    private var enMemoire: AdbKeyPair? = null

    /** La paire de l'application, créée à la première demande et gardée chiffrée ensuite. */
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
            // Une clé illisible — profil Windows restauré sur une autre machine, fichier abîmé —
            // n'est pas effacée mais mise de côté. Une nouvelle la remplace, et chaque téléviseur
            // redemandera simplement l'autorisation.
            Traces.avertir(TAG, "Clé ADB illisible, une nouvelle va la remplacer", erreur)
            val suffixe = ".illisible-${System.currentTimeMillis()}"
            fichierPrive.renameTo(File(dossier, FICHIER_PRIVE + suffixe))
            fichierPublic.renameTo(File(dossier, FICHIER_PUBLIC + suffixe))
            null
        }
    }

    /**
     * Fabrique la paire, la chiffre, puis efface les fichiers que dadb a écrits en clair. C'est le
     * seul instant où le secret touche le disque sans protection.
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
            // La clé privée d'abord : sans sa moitié publique, une paire écrite à moitié est
            // ignorée à la lecture suivante, et simplement refaite.
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
