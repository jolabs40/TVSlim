package net.jolabs40.tvslim.windows.reseau

import net.jolabs40.tvslim.windows.adb.PORT_ADB_PAR_DEFAUT
import java.net.URI
import java.net.URLDecoder

data class AdresseTv(val hote: String, val port: Int)

const val SCHEMA_APPAIRAGE = "tvslim://"

private const val PORT_MAXIMUM = 65_535

/**
 * Parses the address the user entered, as separate host and port fields or in one block.
 *
 * The host field also accepts `192.168.1.20:5555` as copied from the TV screen, and the `tvslim://connect?...`
 * link shown by the optional TV app.
 *
 * A typed address is not filtered, since typing it is deliberate. The link goes through [lireCodeAppairage] and
 * its filter, because it is pasted without being read.
 */
fun interpreterSaisie(hote: String, port: String): AdresseTv? {
    val brut = hote.trim()
    if (brut.isEmpty()) return null
    if (brut.startsWith(SCHEMA_APPAIRAGE)) return lireCodeAppairage(brut)

    // Exactly one `:`. An IPv6 address has several and keeps the port field.
    if (brut.count { it == ':' } == 1) {
        val hoteColle = brut.substringBefore(':').trim()
        val portColle = brut.substringAfter(':').trim().toIntOrNull()
        if (hoteColle.isEmpty() || portColle == null || portColle !in 1..PORT_MAXIMUM) return null
        return AdresseTv(hoteColle, portColle)
    }
    return AdresseTv(brut, port.toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)
}

/**
 * Parses a pairing code in one of three forms, as on the phone: `tvslim://connect?host=...&port=...`,
 * `host:port`, or a bare host.
 *
 * Pasted content is treated as untrusted input: the host must be a local IPv4 address ([estSurLeReseauLocal])
 * and the port in range.
 */
fun lireCodeAppairage(valeur: String): AdresseTv? {
    val brut = valeur.trim()
    val (hote, port) = when {
        brut.startsWith(SCHEMA_APPAIRAGE) -> {
            val parametres = parametresDe(brut)
            parametres["host"].orEmpty() to
                (parametres["port"]?.toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)
        }

        brut.count { it == ':' } == 1 ->
            brut.substringBefore(':') to
                (brut.substringAfter(':').toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)

        else -> brut to PORT_ADB_PAR_DEFAUT
    }
    if (!estSurLeReseauLocal(hote) || port !in 1..PORT_MAXIMUM) return null
    return AdresseTv(hote, port)
}

private fun parametresDe(lien: String): Map<String, String> {
    val requete = runCatching { URI(lien).rawQuery }.getOrNull() ?: return emptyMap()
    return requete.split('&')
        .mapNotNull { paire ->
            val morceaux = paire.split('=', limit = 2)
            if (morceaux.size != 2) return@mapNotNull null
            morceaux[0] to URLDecoder.decode(morceaux[1], Charsets.UTF_8)
        }
        .toMap()
}

/**
 * True for a private, link-local or loopback IPv4 address.
 *
 * Used for pairing links and network scans, so neither can send an ADB handshake (and the app's public key) to
 * a host nobody chose.
 */
fun estSurLeReseauLocal(hote: String): Boolean {
    val octets = IPV4.matchEntire(hote)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
    if (octets.any { it > 255 }) return false
    return when {
        octets[0] == 10 -> true                         // 10.0.0.0/8
        octets[0] == 172 && octets[1] in 16..31 -> true // 172.16.0.0/12
        octets[0] == 192 && octets[1] == 168 -> true    // 192.168.0.0/16
        octets[0] == 169 && octets[1] == 254 -> true    // 169.254.0.0/16, link-local
        octets[0] == 127 -> true                        // loopback, for an emulator
        else -> false
    }
}

/**
 * Turns an address into a file name that cannot contain `/` or `\`. Same rule as the companion:
 * `192.168.2.135` becomes `192_168_2_135`.
 */
fun cleDeFichier(hote: String): String =
    hote.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
