package net.jolabs40.tvslim.windows.adb

import java.io.EOFException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Traduit un échec de connexion en cause compréhensible.
 *
 * L'ordre compte, et il a été appris sur la TCL : dadb enveloppe **tout** échec de poignée de main
 * dans `AdbConnectException`, y compris l'attente d'une autorisation qu'on n'a pas encore acceptée
 * (« Connection handshake failed », causée par « Read timed out »). Se fier à cette seule
 * enveloppe ferait dire « injoignable » d'un téléviseur qui attend simplement qu'on clique.
 *
 * - « Connect timed out » : rien ne répond à cette adresse ;
 * - « Read timed out » : la connexion est ouverte, le téléviseur attend qu'on accepte sa demande ;
 * - fin de flux ou connexion réinitialisée pendant la poignée de main : il l'a refusée.
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
