package net.jolabs40.tvslim.windows.maj

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat

/**
 * Vérifie qu'un installateur téléchargé a bien été signé par la clé de TV Slim.
 *
 * HTTPS garantit qu'on parle à GitHub ; la signature garantit que le fichier vient de qui détient
 * la clé privée — et elle seule. Un compte GitHub compromis pourrait publier un installateur, pas
 * le signer. Sans signature valide, rien ne s'exécute.
 *
 * Le message signé lie la version à l'empreinte du fichier, pour qu'une signature authentique ne
 * puisse pas être recollée sur un autre installateur, même ancien. Il doit rester identique à
 * celui de `outils/SignerMiseAJour.java`, qui signe au moment de la publication.
 */
object VerificationSignature {

    fun message(version: String, empreinte: String): ByteArray =
        "TVSlim-Windows\n$version\n$empreinte".toByteArray(Charsets.UTF_8)

    /** SHA-256 du fichier, en hexadécimal minuscule. */
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
