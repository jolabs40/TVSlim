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
 * Un dossier du disque, où arrive ce qu'on copie du téléviseur.
 *
 * Android admet dans un nom ce que Windows refuse — `:`, `?`, `"`, un point final, `CON` —, et de tels noms
 * existent : une capture horodatée porte souvent des `:`. Chaque étape du chemin passe donc par [nomWindows]
 * plutôt que de laisser l'écriture échouer.
 *
 * Un fichier s'écrit à côté de sa place, sous le suffixe [SUFFIXE_PROVISOIRE], et ne la prend qu'entier : arrêter
 * une copie ne laisse ni fichier tronqué, ni fichier précédent écrasé.
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
        // Remplacer un dossier vide par un fichier, Files.move le ferait sans rien dire.
        if (cible.isDirectory) throw IOException("Un dossier porte déjà ce nom : ${cible.path}")
        val provisoire = File(cible.parentFile, cible.name + SUFFIXE_PROVISOIRE)
        // FileOutputStream dit pourquoi il refuse, dans la langue de Windows : « (Accès refusé) ».
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
                // Le plus souvent, le fichier à remplacer est ouvert dans une autre application.
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

        /** Les noms de périphériques de Windows, avec ou sans extension : `NUL.txt` n'est pas un fichier non plus. */
        private val RESERVES = setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).flatMap { listOf("COM$it", "LPT$it") } + listOf("COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³")

        /**
         * Un nom que Windows accepte : chaque caractère interdit devient `_`, le point et l'espace finaux — que
         * Windows ôterait sans prévenir — tombent, et un nom de périphérique prend un `_` devant.
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
