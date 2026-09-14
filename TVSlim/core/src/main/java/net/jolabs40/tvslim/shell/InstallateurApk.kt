package net.jolabs40.tvslim.shell

import java.io.File

/**
 * Envoi d'un APK vers un téléviseur, et son installation.
 *
 * À part d'[ExecuteurCommande] : une commande shell tient en une ligne et se rejoue sans risque, un
 * envoi de plusieurs dizaines de mégaoctets non. Le canal qui sait faire les deux — la connexion ADB
 * de chaque application — implémente les deux interfaces.
 */
interface InstallateurApk {
    /**
     * Envoie [apk] et l'installe, comme `adb install -r -t` : une application déjà présente est mise à
     * jour et garde ses données. [surEnvoi] suit les octets partis.
     *
     * Renvoie `Success` en cas de réussite, sinon la réponse d'Android — `Failure [INSTALL_…]` — ou
     * [ResultatShell.indisponible] si la connexion a lâché.
     */
    suspend fun installer(apk: File, surEnvoi: (envoye: Long, total: Long) -> Unit): ResultatShell
}
