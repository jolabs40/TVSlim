package net.jolabs40.tvslim.windows.adb

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * dadb écrit sa clé privée en PEM : le DER PKCS#8 encodé en base64, entre deux lignes d'en-tête.
 * `PKCS8EncodedKeySpec` attend le DER nu — d'où cette conversion, isolée ici pour être vérifiable
 * sans téléviseur. Identique à celle du compagnon Android.
 */
fun derDepuisPem(pem: String): ByteArray {
    val corps = pem.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("-----") }
        .joinToString("")
    require(corps.isNotEmpty()) { "Clé PEM vide ou illisible." }
    return Base64.getDecoder().decode(corps)
}

/** Reconstruit la clé privée à partir du DER PKCS#8 déchiffré. */
fun clePriveeDepuisDer(der: ByteArray): PrivateKey =
    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
