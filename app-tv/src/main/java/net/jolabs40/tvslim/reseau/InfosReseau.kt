package net.jolabs40.tvslim.reseau

import android.content.Context
import android.net.ConnectivityManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tout ce que le compagnon a besoin de savoir pour joindre ce téléviseur : son adresse sur le
 * réseau local, et l'état des trois réglages qui conditionnent la connexion.
 *
 * Ces trois-là sont affichés un par un plutôt qu'en bloc : quand la connexion est impossible,
 * il faut savoir lequel manque.
 */
data class PointDeContact(
    val adresse: String = "",
    val port: Int = 0,
    val optionsDeveloppeur: Boolean = false,
    val debogageActive: Boolean = false,
) {
    val debogageReseau: Boolean get() = port > 0

    /** Vrai quand une connexion est possible ici et maintenant. */
    val joignable: Boolean
        get() = adresse.isNotBlank() && debogageReseau && debogageActive

    /** Contenu du QR code, lu par le compagnon. */
    fun uri(): String = "tvslim://connect?host=$adresse&port=$port"
}

@Singleton
class InfosReseau @Inject constructor(
    @ApplicationContext private val contexte: Context,
) {

    fun pointDeContact(): PointDeContact = PointDeContact(
        adresse = adresseLocale().orEmpty(),
        port = portAdb() ?: 0,
        optionsDeveloppeur = reglageActif(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
        debogageActive = reglageActif(Settings.Global.ADB_ENABLED),
    )

    /** Première adresse IPv4 non locale de l'interface active — celle que verra le téléphone. */
    private fun adresseLocale(): String? = runCatching {
        val gestionnaire = contexte.getSystemService(ConnectivityManager::class.java)
        val reseau = gestionnaire?.activeNetwork ?: return@runCatching null
        gestionnaire.getLinkProperties(reseau)
            ?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /**
     * Port d'écoute d'ADB sur TCP. Vide tant que le débogage réseau n'a pas été activé : c'est
     * `service.adb.tcp.port`, que `getprop` expose à toute application.
     */
    private fun portAdb(): Int? = runCatching {
        val processus = ProcessBuilder("getprop", PROPRIETE_PORT)
            .redirectErrorStream(true)
            .start()
        val sortie = processus.inputStream.bufferedReader().use { it.readText() }.trim()
        processus.waitFor()
        sortie.toIntOrNull()?.takeIf { it in 1..65535 }
    }.getOrNull()

    private fun reglageActif(cle: String): Boolean = runCatching {
        Settings.Global.getInt(contexte.contentResolver, cle, 0) == 1
    }.getOrDefault(false)

    private companion object {
        const val PROPRIETE_PORT = "service.adb.tcp.port"
    }
}
