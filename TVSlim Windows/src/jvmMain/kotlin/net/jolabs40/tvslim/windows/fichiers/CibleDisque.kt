package net.jolabs40.tvslim.windows.fichiers

import net.jolabs40.tvslim.fichiers.CibleLocale
import net.jolabs40.tvslim.fichiers.EcritureLocale
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Local folder receiving files copied from the TV.
 *
 * Android allows names Windows rejects (`:`, `?`, `"`, a trailing dot, `CON`), and timestamped screenshots often
 * contain `:`. Each path segment goes through [nomWindows] instead of letting the write fail.
 *
 * A file is written next to its target with [SUFFIXE_PROVISOIRE] and renamed only once complete, so a stopped
 * copy leaves neither a truncated file nor an overwritten previous one.
 */
class CibleDisque(private val racine: File) : CibleLocale {

    override fun decrire(chemin: String): String = fichier(chemin).path

    override fun existe(chemin: String): Boolean = fichier(chemin).exists()

    override fun creerDossier(chemin: String) {
        val dossier = fichier(chemin)
        if (dossier.isDirectory) return
        if (dossier.exists()) throw IOException("Un fichier porte déjà ce nom : ${dossier.path}")
        try {
            Files.createDirectories(dossier.toPath())
        } catch (erreur: FileSystemException) {
            throw IOException(erreur.reason ?: "Création impossible : ${dossier.path}", erreur)
        }
    }

    override fun ecrire(chemin: String): EcritureLocale {
        val cible = fichier(chemin)
        // Files.move would silently replace an empty folder with the file.
        if (cible.isDirectory) throw IOException("Un dossier porte déjà ce nom : ${cible.path}")
        val provisoire = File(cible.parentFile, cible.name + SUFFIXE_PROVISOIRE)
        // FileOutputStream's error message is in the Windows display language.
        return EcritureDisque(cible, provisoire, FileOutputStream(provisoire).buffered(TAMPON))
    }

    private fun fichier(chemin: String): File =
        chemin.split('/').filter { it.isNotEmpty() }.fold(racine) { dossier, nom -> File(dossier, nomWindows(nom)) }

    private class EcritureDisque(
        private val cible: File,
        private val provisoire: File,
        override val flux: OutputStream,
    ) : EcritureLocale {

        private var valide = false

        override fun valider(date: Long) {
            flux.close()
            try {
                Files.move(provisoire.toPath(), cible.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (erreur: FileSystemException) {
                // Usually the target file is open in another application.
                throw IOException(erreur.reason ?: "Remplacement impossible : ${cible.path}", erreur)
            }
            valide = true
            if (date > 0) cible.setLastModified(date)
        }

        override fun close() {
            runCatching { flux.close() }
            if (!valide) provisoire.delete()
        }
    }

    companion object {
        const val SUFFIXE_PROVISOIRE = ".tvslim-partiel"

        private const val TAMPON = 256 * 1024

        private const val INTERDITS = "<>:\"/\\|?*"

        /** Windows device names, reserved with or without an extension (`NUL.txt` too). */
        private val RESERVES = setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).flatMap { listOf("COM$it", "LPT$it") } + listOf("COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³")

        /**
         * Returns a name Windows accepts: forbidden and control characters become `_`, trailing dots and spaces
         * (which Windows would strip silently) are removed, and device names get a `_` prefix.
         */
        fun nomWindows(nom: String): String {
            val propre = nom
                .map { if (it in INTERDITS || it.code < 0x20) '_' else it }
                .joinToString("")
                .trimEnd('.', ' ')
                .ifEmpty { "_" }
            return if (propre.substringBefore('.').trimEnd().uppercase() in RESERVES) "_$propre" else propre
        }
    }
}
