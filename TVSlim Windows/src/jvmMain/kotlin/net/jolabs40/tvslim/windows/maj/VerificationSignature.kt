package net.jolabs40.tvslim.windows.maj

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
 * installer, even an older one. It must stay identical to the one in `outils/SignerMiseAJour.java`, which signs
 * at release time.
 */
object VerificationSignature {

    fun message(version: String, empreinte: String): ByteArray =
        "TVSlim-Windows\n$version\n$empreinte".toByteArray(Charsets.UTF_8)

    /** SHA-256 of the file, lowercase hex. */
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
        return HexFormat.of().formatHex(condensat.digest())
    }

    fun verifier(
        fichier: File,
        version: String,
        signatureBase64: String,
        clePubliqueBase64: String,
    ): Boolean = runCatching {
        require(clePubliqueBase64.isNotBlank()) { "aucune clé publique embarquée" }
        val cle = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(clePubliqueBase64.trim())))
        Signature.getInstance("Ed25519").run {
            initVerify(cle)
            update(message(version, empreinte(fichier)))
            verify(Base64.getDecoder().decode(signatureBase64.trim()))
        }
    }.getOrDefault(false)
}
