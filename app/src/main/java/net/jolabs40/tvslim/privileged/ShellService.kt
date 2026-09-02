package net.jolabs40.tvslim.privileged

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Service exécuté par Shizuku dans un process séparé tournant en UID shell (2000), soit
 * exactement les privilèges d'une session ADB. Il ne dépend volontairement de rien : ni Hilt,
 * ni Context, ni ressources — l'Application de l'app n'est pas créée dans ce process.
 *
 * Le contrat de retour est « code de sortie, saut de ligne, sortie fusionnée ».
 */
class ShellService : IShellService.Stub() {

    override fun executer(commande: String): String = try {
        val processus = ProcessBuilder("sh", "-c", commande)
            .redirectErrorStream(true)
            .start()
        val sortie = BufferedReader(InputStreamReader(processus.inputStream)).use { it.readText() }
        val code = processus.waitFor()
        "$code\n$sortie"
    } catch (erreur: Exception) {
        "-1\n${erreur.message ?: erreur.javaClass.simpleName}"
    }

    override fun destroy() {
        System.exit(0)
    }
}
