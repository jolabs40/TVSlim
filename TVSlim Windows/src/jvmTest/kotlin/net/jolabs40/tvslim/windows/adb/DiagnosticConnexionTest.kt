package net.jolabs40.tvslim.windows.adb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * Ce que la fenêtre dit d'une connexion ratée. Les chaînes d'exceptions reprennent celles de
 * dadb 2.0.0, dont celle relevée sur la TCL pendant une demande d'autorisation en attente.
 */
class DiagnosticConnexionTest {

    /** Même nom simple que l'enveloppe de dadb : c'est sur lui que le diagnostic la reconnaît. */
    private class AdbConnectException(message: String, cause: Throwable) : IOException(message, cause)

    @Test
    fun `une autorisation en attente n'est pas prise pour un televiseur injoignable`() {
        // Relevé sur la TCL le 2026-09-13, dialogue d'autorisation ouvert à l'écran.
        val erreur = AdbConnectException("Connection handshake failed", SocketTimeoutException("Read timed out"))

        assertEquals(ProblemeConnexion.DELAI, diagnostiquer(erreur))
    }

    @Test
    fun `personne ne repond a l'adresse`() {
        val erreur = AdbConnectException("Failed to connect to 192.168.2.99:5555", SocketTimeoutException("Connect timed out"))

        assertEquals(ProblemeConnexion.INJOIGNABLE, diagnostiquer(erreur))
        assertEquals(ProblemeConnexion.INJOIGNABLE, diagnostiquer(NoRouteToHostException("No route to host")))
    }

    @Test
    fun `le port est ferme`() {
        val erreur = AdbConnectException("Failed to connect", ConnectException("Connection refused: connect"))

        assertEquals(ProblemeConnexion.REFUSEE, diagnostiquer(erreur))
    }

    @Test
    fun `le televiseur ferme la connexion pendant la poignee de main`() {
        val erreur = AdbConnectException("Connection handshake failed", EOFException())

        assertEquals(ProblemeConnexion.NON_AUTORISEE, diagnostiquer(erreur))
    }

    @Test
    fun `la surveillance l'emporte sur l'exception qu'elle a provoquee`() {
        assertEquals(ProblemeConnexion.DELAI, diagnostiquer(java.net.SocketException("Socket closed"), delaiDepasse = true))
    }

    @Test
    fun `le reste n'est pas deguise`() {
        assertEquals(ProblemeConnexion.AUTRE, diagnostiquer(IllegalStateException("inattendu")))
    }
}
