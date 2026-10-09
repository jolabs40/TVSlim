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
internal fun diagnose(error: Throwable, timedOut: Boolean = false): ConnectionProblem {
    if (timedOut) return ConnectionProblem.TIMEOUT
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val texts = chain.joinToString(" ") { it.message.orEmpty() }

    return when {
        texts.contains("refused", ignoreCase = true) -> ConnectionProblem.REJECTED

        chain.any { it is NoRouteToHostException || it is UnknownHostException } ||
            texts.contains("unreachable", ignoreCase = true) -> ConnectionProblem.UNREACHABLE

        chain.any { it is SocketTimeoutException && it.message.orEmpty().contains("connect", ignoreCase = true) } ->
            ConnectionProblem.UNREACHABLE

        chain.any { it is SocketTimeoutException } -> ConnectionProblem.TIMEOUT

        chain.any { it is EOFException || it is SocketException } ||
            texts.contains("unauthorized", ignoreCase = true) ||
            texts.contains("handshake", ignoreCase = true) -> ConnectionProblem.UNAUTHORIZED

        chain.any { it.javaClass.simpleName == "AdbConnectException" } -> ConnectionProblem.UNREACHABLE

        else -> ConnectionProblem.OTHER
    }
}
