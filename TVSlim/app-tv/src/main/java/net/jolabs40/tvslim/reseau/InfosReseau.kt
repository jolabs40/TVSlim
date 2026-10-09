package net.jolabs40.tvslim.reseau

import android.content.Context
import android.net.ConnectivityManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the companion needs to reach this TV: its LAN address and the three settings the connection
 * depends on. The settings are shown one by one so that a failed connection tells which one is missing.
 */
data class PointDeContact(
    val adresse: String = "",
    val port: Int = 0,
    val optionsDeveloppeur: Boolean = false,
    val debogageActive: Boolean = false,
) {
    val debogageReseau: Boolean get() = port > 0

    /** True when a connection is possible right now. */
    val joignable: Boolean
        get() = adresse.isNotBlank() && debogageReseau && debogageActive

    /** QR code payload, scanned by the companion. */
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

    /** First non-loopback, non-link-local IPv4 address of the active network, as the phone sees it. */
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
     * ADB's TCP port from `service.adb.tcp.port`, which `getprop` exposes to any app. Empty until
     * network debugging is enabled.
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
