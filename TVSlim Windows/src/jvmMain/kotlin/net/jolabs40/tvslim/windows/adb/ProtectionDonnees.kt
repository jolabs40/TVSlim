package net.jolabs40.tvslim.windows.adb

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

/** Chiffre au repos ce qui ne doit se relire que sous ce compte, sur cette machine. */
interface ProtectionDonnees {
    fun proteger(donnees: ByteArray): ByteArray
    fun lever(protegees: ByteArray): ByteArray
}

/**
 * DPAPI : Windows chiffre avec une clé dérivée de la session de l'utilisateur. Le fichier copié
 * sur une autre machine, sauvegardé dans un nuage ou lu depuis un autre compte ne se déchiffre
 * pas. C'est le pendant du keystore Android, qui protège la même clé sur le téléphone.
 *
 * Honnêtement : cela ne protège pas d'un programme malveillant lancé sous le même compte — rien
 * ne le peut, sur un poste ordinaire. L'entropie n'est pas un secret (le code est public) ; elle
 * évite seulement qu'une autre application de ce compte relise ces octets par mégarde.
 */
object ProtectionDpapi : ProtectionDonnees {

    private val ENTROPIE = "TVSlim/cle-adb".toByteArray(Charsets.UTF_8)

    override fun proteger(donnees: ByteArray): ByteArray = Crypt32Util.cryptProtectData(
        donnees,
        ENTROPIE,
        WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
        "TV Slim",
        null,
    )

    override fun lever(protegees: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(
        protegees,
        ENTROPIE,
        WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
        null,
    )
}
