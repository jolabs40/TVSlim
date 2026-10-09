package net.jolabs40.tvslim.remote.adb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * How the companion classifies a failed connection. Exception chains mirror dadb 2.0.0's, including
 * one seen on the TCL while an authorization prompt was pending.
 */
class ConnectionDiagnosticTest {

    /** Same simple name as dadb's wrapper, which is what the diagnosis matches on. */
    private class AdbConnectException(message: String, cause: Throwable) : IOException(message, cause)

    @Test
    fun `a pending authorization is not mistaken for an unreachable TV`() {
        // Seen on the TCL with the authorization dialog on screen.
        val error = AdbConnectException("Connection handshake failed", SocketTimeoutException("Read timed out"))

        assertEquals(ConnectionProblem.TIMEOUT, diagnose(error))
    }

    @Test
    fun `nobody answers at the address`() {
        val error = AdbConnectException("Failed to connect to 192.168.2.99:5555", SocketTimeoutException("Connect timed out"))

        assertEquals(ConnectionProblem.UNREACHABLE, diagnose(error))
        assertEquals(ConnectionProblem.UNREACHABLE, diagnose(NoRouteToHostException("No route to host")))
    }

    @Test
    fun `the port is closed`() {
        val error = AdbConnectException("Failed to connect", ConnectException("Connection refused: connect"))

        assertEquals(ConnectionProblem.REJECTED, diagnose(error))
    }

    @Test
    fun `the TV closes the connection during the handshake`() {
        val error = AdbConnectException("Connection handshake failed", EOFException())

        assertEquals(ConnectionProblem.UNAUTHORIZED, diagnose(error))
    }

    @Test
    fun `the watchdog takes precedence over the exception it caused`() {
        assertEquals(ConnectionProblem.TIMEOUT, diagnose(java.net.SocketException("Socket closed"), timedOut = true))
    }

    @Test
    fun `other errors are not disguised`() {
        assertEquals(ConnectionProblem.OTHER, diagnose(IllegalStateException("unexpected")))
    }
}
