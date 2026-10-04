package net.jolabs40.tvslim.windows.ecran

import net.jolabs40.tvslim.windows.maj.ClientGithub
import net.jolabs40.tvslim.windows.outils.Traces
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * La version de scrcpy que TV Slim télécharge pour le miroir quand aucune n'est installée, et l'empreinte publiée avec elle
 * (`SHA256SUMS.txt` de la publication v4.1 de Genymobile/scrcpy). Changer de version, c'est changer les trois
 * ensemble.
 */
object ScrcpyEpingle {
    const val VERSION = "4.1"
    const val DOSSIER = "scrcpy-win64-v4.1"
    const val URL = "https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-win64-v4.1.zip"
    const val SHA256 = "5b12172b3264b2889f4583ee64752ce832e29bc8b1089dca81093459697165db"

    /** 11,3 Mo publiés : au-delà de 20, ce n'est pas le fichier attendu. */
    const val TAILLE_MAX = 20_000_000L

    /** La plus ancienne qui comprenne `--no-audio` (2.0). */
    val VERSION_MINIMALE = 2 to 0
}

/**
 * Les arguments du miroir. Le son reste sur le téléviseur (`--no-audio`) : on regarde l'écran sur le PC, on écoute
 * dans la pièce. La vidéo, elle, ne passe pas par scrcpy (`EnregistrementTv`, dans le noyau).
 */
object ArgumentsScrcpy {
    fun miroir(hote: String, port: Int, titre: String): List<String> =
        listOf("--tcpip=$hote:$port", "--window-title=$titre", "--no-audio")
}

/**
 * Où trouver scrcpy : la copie que TV Slim a téléchargée, puis le PATH, puis une installation par winget. Une
 * version trop ancienne pour les options passées est ignorée — mieux vaut proposer la version épinglée qu'un
 * échec obscur au lancement.
 */
class LocalisationScrcpy(
    private val dossierTelecharge: File,
    private val environnement: (String) -> String? = System::getenv,
    private val lireVersion: (File) -> Pair<Int, Int>? = ::versionDe,
) {

    fun trouver(): File? = candidats().firstOrNull { exe ->
        exe.isFile && lireVersion(exe)?.let { suffisante(it) } == true
    }

    internal fun candidats(): List<File> = buildList {
        add(File(dossierTelecharge, "${ScrcpyEpingle.DOSSIER}/scrcpy.exe"))
        environnement("PATH").orEmpty().split(';')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .forEach { add(File(it, "scrcpy.exe")) }
        environnement("LOCALAPPDATA")?.let { local ->
            val paquets = File(local, "Microsoft/WinGet/Packages")
            paquets.listFiles { f -> f.isDirectory && f.name.startsWith("Genymobile.scrcpy") }.orEmpty()
                .flatMap { paquet -> paquet.listFiles { f -> f.isDirectory && f.name.startsWith("scrcpy-win64") }.orEmpty().toList() }
                .sortedByDescending { it.name }
                .forEach { add(File(it, "scrcpy.exe")) }
        }
    }.distinctBy { it.absolutePath.lowercase() }

    companion object {
        fun suffisante(version: Pair<Int, Int>): Boolean {
            val (majeure, mineure) = ScrcpyEpingle.VERSION_MINIMALE
            return version.first > majeure || (version.first == majeure && version.second >= mineure)
        }

        /** « scrcpy 4.1 <https://github.com/Genymobile/scrcpy> » → 4 to 1. */
        fun lireLigneVersion(sortie: String): Pair<Int, Int>? =
            Regex("""scrcpy\s+(\d+)\.(\d+)""").find(sortie)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        private fun versionDe(exe: File): Pair<Int, Int>? = runCatching {
            val processus = ProcessBuilder(exe.absolutePath, "--version").redirectErrorStream(true).start()
            val sortie = processus.inputStream.bufferedReader().use { it.readText() }
            if (!processus.waitFor(5, TimeUnit.SECONDS)) processus.destroyForcibly()
            lireLigneVersion(sortie)
        }.onFailure { Traces.avertir("Scrcpy", "Version illisible", it) }.getOrNull()
    }
}

/** L'archive téléchargée ne correspond pas à l'empreinte publiée : elle est effacée, rien n'est installé. */
class EmpreinteInattendue : IOException("empreinte SHA-256 inattendue")

/**
 * Télécharge scrcpy depuis la publication GitHub de Genymobile, vérifie l'empreinte, puis décompresse dans
 * `%LOCALAPPDATA%\TVSlim\scrcpy`. Rien n'apparaît sous le nom final tant que tout n'a pas réussi.
 */
class InstallationScrcpy(private val github: ClientGithub, private val dossier: File) {

    suspend fun installer(progression: (Float) -> Unit): File {
        dossier.mkdirs()
        val archive = File(dossier, "${ScrcpyEpingle.DOSSIER}.zip")
        try {
            github.telecharger(ScrcpyEpingle.URL, archive, ScrcpyEpingle.TAILLE_MAX, progression)
            if (empreinte(archive) != ScrcpyEpingle.SHA256) throw EmpreinteInattendue()
            return decompresser(archive)
        } finally {
            archive.delete()
        }
    }

    internal fun decompresser(archive: File): File {
        val provisoire = File(dossier, "${ScrcpyEpingle.DOSSIER}.extraction")
        provisoire.deleteRecursively()
        provisoire.mkdirs()
        val racine = provisoire.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entree = zip.nextEntry ?: break
                val cible = File(provisoire, entree.name).canonicalFile
                // Une entrée en « ../ » ne sort pas du dossier d'extraction.
                if (!cible.path.startsWith(racine.path + File.separator)) throw IOException("entrée refusée : ${entree.name}")
                if (entree.isDirectory) {
                    cible.mkdirs()
                } else {
                    cible.parentFile.mkdirs()
                    cible.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        // L'archive range tout sous « scrcpy-win64-v4.1/ » ; une archive sans dossier serait prise telle quelle.
        val contenu = File(provisoire, ScrcpyEpingle.DOSSIER).takeIf { File(it, "scrcpy.exe").isFile } ?: provisoire
        if (!File(contenu, "scrcpy.exe").isFile) throw IOException("scrcpy.exe absent de l'archive")
        val final = File(dossier, ScrcpyEpingle.DOSSIER)
        final.deleteRecursively()
        Files.move(contenu.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
        provisoire.deleteRecursively()
        return File(final, "scrcpy.exe")
    }

    companion object {
        fun empreinte(fichier: File): String {
            val condensat = MessageDigest.getInstance("SHA-256")
            fichier.inputStream().use { flux ->
                val tampon = ByteArray(64 * 1024)
                while (true) {
                    val lu = flux.read(tampon)
                    if (lu < 0) break
                    condensat.update(tampon, 0, lu)
                }
            }
            return condensat.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
