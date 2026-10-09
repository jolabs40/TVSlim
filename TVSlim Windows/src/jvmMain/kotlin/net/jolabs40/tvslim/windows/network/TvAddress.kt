package net.jolabs40.tvslim.windows.network

import net.jolabs40.tvslim.windows.adb.DEFAULT_ADB_PORT
import java.net.URI
import java.net.URLDecoder

data class TvAddress(val host: String, val port: Int)

const val PAIRING_SCHEME = "tvslim://"

private const val PORT_MAXIMUM = 65_535

/**
 * Parses the address the user entered, as separate host and port fields or in one block.
 *
 * The host field also accepts `192.168.1.20:5555` as copied from the TV screen, and the `tvslim://connect?...`
 * link shown by the optional TV app.
 *
 * A typed address is not filtered, since typing it is deliberate. The link goes through [readPairingCode] and
 * its filter, because it is pasted without being read.
 */
fun parseInput(host: String, port: String): TvAddress? {
    val raw = host.trim()
    if (raw.isEmpty()) return null
    if (raw.startsWith(PAIRING_SCHEME)) return readPairingCode(raw)

    // Exactly one `:`. An IPv6 address has several and keeps the port field.
    if (raw.count { it == ':' } == 1) {
        val attachedHost = raw.substringBefore(':').trim()
        val attachedPort = raw.substringAfter(':').trim().toIntOrNull()
        if (attachedHost.isEmpty() || attachedPort == null || attachedPort !in 1..PORT_MAXIMUM) return null
        return TvAddress(attachedHost, attachedPort)
    }
    return TvAddress(raw, port.toIntOrNull() ?: DEFAULT_ADB_PORT)
}

/**
 * Parses a pairing code in one of three forms, as on the phone: `tvslim://connect?host=...&port=...`,
 * `host:port`, or a bare host.
 *
 * Pasted content is treated as untrusted input: the host must be a local IPv4 address ([isOnLocalNetwork])
 * and the port in range.
 */
fun readPairingCode(rawValue: String): TvAddress? {
    val raw = rawValue.trim()
    val (host, port) = when {
        raw.startsWith(PAIRING_SCHEME) -> {
            val parameters = parametersOf(raw)
            parameters["host"].orEmpty() to
                (parameters["port"]?.toIntOrNull() ?: DEFAULT_ADB_PORT)
        }

        raw.count { it == ':' } == 1 ->
            raw.substringBefore(':') to
                (raw.substringAfter(':').toIntOrNull() ?: DEFAULT_ADB_PORT)

        else -> raw to DEFAULT_ADB_PORT
    }
    if (!isOnLocalNetwork(host) || port !in 1..PORT_MAXIMUM) return null
    return TvAddress(host, port)
}

private fun parametersOf(link: String): Map<String, String> {
    val request = runCatching { URI(link).rawQuery }.getOrNull() ?: return emptyMap()
    return request.split('&')
        .mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            parts[0] to URLDecoder.decode(parts[1], Charsets.UTF_8)
        }
        .toMap()
}

/**
 * True for a private, link-local or loopback IPv4 address.
 *
 * Used for pairing links and network scans, so neither can send an ADB handshake (and the app's public key) to
 * a host nobody chose.
 */
fun isOnLocalNetwork(host: String): Boolean {
    val bytes = IPV4.matchEntire(host)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
    if (bytes.any { it > 255 }) return false
    return when {
        bytes[0] == 10 -> true                         // 10.0.0.0/8
        bytes[0] == 172 && bytes[1] in 16..31 -> true // 172.16.0.0/12
        bytes[0] == 192 && bytes[1] == 168 -> true    // 192.168.0.0/16
        bytes[0] == 169 && bytes[1] == 254 -> true    // 169.254.0.0/16, link-local
        bytes[0] == 127 -> true                        // loopback, for an emulator
        else -> false
    }
}

/**
 * Turns an address into a file name that cannot contain `/` or `\`. Same rule as the companion:
 * `192.168.2.135` becomes `192_168_2_135`.
 */
fun fileKey(host: String): String =
    host.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
