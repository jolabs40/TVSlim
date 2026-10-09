package net.jolabs40.tvslim.windows.update

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat

/**
 * Checks that a downloaded installer was signed with TV Slim's Ed25519 key.
 *
 * HTTPS authenticates GitHub; the signature authenticates the holder of the private key. A compromised GitHub
 * account could publish an installer but not sign it. Nothing runs without a valid signature.
 *
 * The signed message binds the version to the file hash, so a genuine signature cannot be reused on another
 * installer, even an older one. It must stay identical to the one in `tools/SignUpdate.java`, which signs
 * at release time.
 */
object SignatureVerification {

    fun message(version: String, fingerprint: String): ByteArray =
        "TVSlim-Windows\n$version\n$fingerprint".toByteArray(Charsets.UTF_8)

    /** SHA-256 of the file, lowercase hex. */
    fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val justRead = stream.read(buffer)
                if (justRead < 0) break
                digest.update(buffer, 0, justRead)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    fun check(
        file: File,
        version: String,
        signatureBase64: String,
        publicKeyBase64: String,
    ): Boolean = runCatching {
        require(publicKeyBase64.isNotBlank()) { "aucune clé publique embarquée" }
        val key = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())))
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message(version, fingerprint(file)))
            verify(Base64.getDecoder().decode(signatureBase64.trim()))
        }
    }.getOrDefault(false)
}
