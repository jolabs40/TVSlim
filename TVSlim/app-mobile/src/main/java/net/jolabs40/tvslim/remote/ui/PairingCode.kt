package net.jolabs40.tvslim.remote.ui

import android.net.Uri
import net.jolabs40.tvslim.remote.adb.DEFAULT_ADB_PORT

data class TvAddress(val host: String, val port: Int)

const val PAIRING_SCHEME = "tvslim://"

/**
 * Parses the pairing code shown by the TV app: `tvslim://connect?host=...&port=...`, `host:port`, or a bare host.
 *
 * The content comes from an image, and any sticker scans as well as a TV screen, so it is untrusted input:
 * the host must be a local IPv4 address ([isOnLocalNetwork]) and the port valid. Returns null otherwise.
 */
fun readPairingCode(rawValue: String): TvAddress? {
    val raw = rawValue.trim()
    val (host, port) = when {
        raw.startsWith(PAIRING_SCHEME) -> {
            val uri = Uri.parse(raw)
            uri.getQueryParameter("host").orEmpty() to
                (uri.getQueryParameter("port")?.toIntOrNull() ?: DEFAULT_ADB_PORT)
        }

        raw.count { it == ':' } == 1 ->
            raw.substringBefore(':') to
                (raw.substringAfter(':').toIntOrNull() ?: DEFAULT_ADB_PORT)

        else -> raw to DEFAULT_ADB_PORT
    }
    if (!isOnLocalNetwork(host) || port !in 1..PORT_MAXIMUM) return null
    return TvAddress(host, port)
}

/**
 * Accepts only private, link-local or loopback IPv4 addresses. The TV app's QR code always holds an IPv4 read
 * from the active interface, never a host name.
 *
 * Without this filter, a forged code (`tvslim://connect?host=example.invalid`) would send an ADB handshake,
 * with the app's public key, to an arbitrary host whose answers the state reader would then parse.
 * Manual entry skips this check: typing an address is deliberate.
 */
internal fun isOnLocalNetwork(host: String): Boolean {
    val bytes = IPV4.matchEntire(host)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
    if (bytes.any { it > 255 }) return false
    return when {
        bytes[0] == 10 -> true                        // 10.0.0.0/8
        bytes[0] == 172 && bytes[1] in 16..31 -> true // 172.16.0.0/12
        bytes[0] == 192 && bytes[1] == 168 -> true    // 192.168.0.0/16
        bytes[0] == 169 && bytes[1] == 254 -> true    // 169.254.0.0/16, link-local
        bytes[0] == 127 -> true                        // loopback, for an emulator
        else -> false
    }
}

/**
 * Turns a host into a safe file name: anything but letters and digits becomes `_`.
 *
 * The host may come from a scanned code, so `/` and `\` must not create subfolders (`a/b` would write to
 * `journals/a/b.json`). `192.168.2.135` must stay `192_168_2_135`: existing logs and measurements are stored
 * under that name.
 */
fun fileKey(host: String): String =
    host.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

private const val PORT_MAXIMUM = 65_535
