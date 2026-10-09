package net.jolabs40.tvslim.remote.adb

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Extracts the DER bytes from the PEM key dadb writes (base64 PKCS#8 between header lines).
 * `PKCS8EncodedKeySpec` wants raw DER. Kept separate so it can be tested without a device.
 */
fun derDepuisPem(pem: String): ByteArray {
    val corps = pem.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("-----") }
        .joinToString("")
    require(corps.isNotEmpty()) { "Clé PEM vide ou illisible." }
    return Base64.getDecoder().decode(corps)
}

/** Rebuilds the private key from the stored PKCS#8 DER. */
fun clePriveeDepuisDer(der: ByteArray): PrivateKey =
    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
