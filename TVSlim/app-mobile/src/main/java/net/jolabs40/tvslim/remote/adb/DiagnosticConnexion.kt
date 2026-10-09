package net.jolabs40.tvslim.remote.adb

import java.io.EOFException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Maps a connection failure to a user-facing cause. Same logic as the Windows client.
 *
 * Order matters (seen on the TCL): dadb wraps every handshake failure in `AdbConnectException`,
 * including a pending authorization prompt ("Connection handshake failed" caused by "Read timed out").
 * Trusting the wrapper alone would report a TV that is waiting for the user as unreachable.
 *
 * - "Connect timed out": nothing answers at this address.
 * - "Read timed out": connected, the TV is waiting for the user to accept the prompt.
 * - End of stream or reset during the handshake: the TV refused.
 */
internal fun diagnostiquer(erreur: Throwable, delaiDepasse: Boolean = false): ProblemeConnexion {
    if (delaiDepasse) return ProblemeConnexion.DELAI
    val chaine = generateSequence(erreur) { it.cause }.take(8).toList()
    val textes = chaine.joinToString(" ") { it.message.orEmpty() }

    return when {
        textes.contains("refused", ignoreCase = true) -> ProblemeConnexion.REFUSEE

        chaine.any { it is NoRouteToHostException || it is UnknownHostException } ||
            textes.contains("unreachable", ignoreCase = true) -> ProblemeConnexion.INJOIGNABLE

        chaine.any { it is SocketTimeoutException && it.message.orEmpty().contains("connect", ignoreCase = true) } ->
            ProblemeConnexion.INJOIGNABLE

        chaine.any { it is SocketTimeoutException } -> ProblemeConnexion.DELAI

        chaine.any { it is EOFException || it is SocketException } ||
            textes.contains("unauthorized", ignoreCase = true) ||
            textes.contains("handshake", ignoreCase = true) -> ProblemeConnexion.NON_AUTORISEE

        chaine.any { it.javaClass.simpleName == "AdbConnectException" } -> ProblemeConnexion.INJOIGNABLE

        else -> ProblemeConnexion.AUTRE
    }
}
