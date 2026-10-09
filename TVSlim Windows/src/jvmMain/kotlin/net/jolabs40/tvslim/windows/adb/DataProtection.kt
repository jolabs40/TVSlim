package net.jolabs40.tvslim.windows.adb

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

/** Encrypts data at rest so it can only be read back by this user on this machine. */
interface DataProtection {
    fun protect(data: ByteArray): ByteArray
    fun unprotect(protectedData: ByteArray): ByteArray
}

/**
 * DPAPI: Windows encrypts with a key derived from the user's logon credentials. The file cannot be decrypted on
 * another machine, from a cloud backup or from another account. Counterpart of the Android keystore on the phone.
 *
 * It does not protect against malware running under the same account. The entropy is not a secret (the code is
 * public); it only keeps other apps of this account from decrypting these bytes by accident.
 */
object DpapiProtection : DataProtection {

    private val ENTROPY = "TVSlim/cle-adb".toByteArray(Charsets.UTF_8)

    override fun protect(data: ByteArray): ByteArray = Crypt32Util.cryptProtectData(
        data,
        ENTROPY,
        WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
        "TV Slim",
        null,
    )

    override fun unprotect(protectedData: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(
        protectedData,
        ENTROPY,
        WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
        null,
    )
}
