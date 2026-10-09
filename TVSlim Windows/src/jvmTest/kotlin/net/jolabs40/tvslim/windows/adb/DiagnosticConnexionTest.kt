package net.jolabs40.tvslim.windows.adb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * How a failed connection is classified. Exception chains mirror dadb 2.0.0's, including one seen on the TCL
 * while an authorization prompt was pending.
 */
class DiagnosticConnexionTest {

    /** Same simple name as dadb's wrapper, which is what the diagnosis matches on. */
    private class AdbConnectException(message: String, cause: Throwable) : IOException(message, cause)

    @Test
    fun `a pending authorization is not mistaken for an unreachable TV`() {
        // Seen on the TCL with the authorization dialog on screen.
        val erreur = AdbConnectException("Connection handshake failed", SocketTimeoutException("Read timed out"))

        assertEquals(ProblemeConnexion.DELAI, diagnostiquer(erreur))
    }

    @Test
    fun `nobody answers at the address`() {
        val erreur = AdbConnectException("Failed to connect to 192.168.2.99:5555", SocketTimeoutException("Connect timed out"))

        assertEquals(ProblemeConnexion.INJOIGNABLE, diagnostiquer(erreur))
        assertEquals(ProblemeConnexion.INJOIGNABLE, diagnostiquer(NoRouteToHostException("No route to host")))
    }

    @Test
    fun `the port is closed`() {
        val erreur = AdbConnectException("Failed to connect", ConnectException("Connection refused: connect"))

        assertEquals(ProblemeConnexion.REFUSEE, diagnostiquer(erreur))
    }

    @Test
    fun `the TV closes the connection during the handshake`() {
        val erreur = AdbConnectException("Connection handshake failed", EOFException())

        assertEquals(ProblemeConnexion.NON_AUTORISEE, diagnostiquer(erreur))
    }

    @Test
    fun `the watchdog takes precedence over the exception it caused`() {
        assertEquals(ProblemeConnexion.DELAI, diagnostiquer(java.net.SocketException("Socket closed"), delaiDepasse = true))
    }

    @Test
    fun `other errors are not disguised`() {
        assertEquals(ProblemeConnexion.AUTRE, diagnostiquer(IllegalStateException("inattendu")))
    }
}
