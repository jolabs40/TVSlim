package net.jolabs40.tvslim.remote.ui

import android.net.Uri
import net.jolabs40.tvslim.remote.adb.PORT_ADB_PAR_DEFAUT

data class AdresseTv(val hote: String, val port: Int)

const val SCHEMA_APPAIRAGE = "tvslim://"

/**
 * Parses the pairing code shown by the TV app: `tvslim://connect?host=...&port=...`, `host:port`, or a bare host.
 *
 * The content comes from an image, and any sticker scans as well as a TV screen, so it is untrusted input:
 * the host must be a local IPv4 address ([estSurLeReseauLocal]) and the port valid. Returns null otherwise.
 */
fun lireCodeAppairage(valeur: String): AdresseTv? {
    val brut = valeur.trim()
    val (hote, port) = when {
        brut.startsWith(SCHEMA_APPAIRAGE) -> {
            val uri = Uri.parse(brut)
            uri.getQueryParameter("host").orEmpty() to
                (uri.getQueryParameter("port")?.toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)
        }

        brut.count { it == ':' } == 1 ->
            brut.substringBefore(':') to
                (brut.substringAfter(':').toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)

        else -> brut to PORT_ADB_PAR_DEFAUT
    }
    if (!estSurLeReseauLocal(hote) || port !in 1..PORT_MAXIMUM) return null
    return AdresseTv(hote, port)
}

/**
 * Accepts only private, link-local or loopback IPv4 addresses. The TV app's QR code always holds an IPv4 read
 * from the active interface, never a host name.
 *
 * Without this filter, a forged code (`tvslim://connect?host=example.invalid`) would send an ADB handshake,
 * with the app's public key, to an arbitrary host whose answers the state reader would then parse.
 * Manual entry skips this check: typing an address is deliberate.
 */
internal fun estSurLeReseauLocal(hote: String): Boolean {
    val octets = IPV4.matchEntire(hote)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
    if (octets.any { it > 255 }) return false
    return when {
        octets[0] == 10 -> true                        // 10.0.0.0/8
        octets[0] == 172 && octets[1] in 16..31 -> true // 172.16.0.0/12
        octets[0] == 192 && octets[1] == 168 -> true    // 192.168.0.0/16
        octets[0] == 169 && octets[1] == 254 -> true    // 169.254.0.0/16, link-local
        octets[0] == 127 -> true                        // loopback, for an emulator
        else -> false
    }
}

/**
 * Turns a host into a safe file name: anything but letters and digits becomes `_`.
 *
 * The host may come from a scanned code, so `/` and `\` must not create subfolders (`a/b` would write to
 * `journaux/a/b.json`). `192.168.2.135` must stay `192_168_2_135`: existing logs and measurements are stored
 * under that name.
 */
fun cleDeFichier(hote: String): String =
    hote.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

private const val PORT_MAXIMUM = 65_535
