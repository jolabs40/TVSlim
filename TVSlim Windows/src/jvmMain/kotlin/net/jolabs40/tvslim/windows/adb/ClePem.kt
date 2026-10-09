package net.jolabs40.tvslim.windows.adb

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Extracts the PKCS#8 DER from dadb's PEM private key, since `PKCS8EncodedKeySpec` expects raw DER.
 * Same conversion as the Android companion.
 */
fun derDepuisPem(pem: String): ByteArray {
    val corps = pem.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("-----") }
        .joinToString("")
    require(corps.isNotEmpty()) { "Clé PEM vide ou illisible." }
    return Base64.getDecoder().decode(corps)
}

fun clePriveeDepuisDer(der: ByteArray): PrivateKey =
    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
