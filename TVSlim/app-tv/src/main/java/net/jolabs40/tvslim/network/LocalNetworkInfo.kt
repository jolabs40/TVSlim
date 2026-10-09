package net.jolabs40.tvslim.network

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
data class ContactPoint(
    val address: String = "",
    val port: Int = 0,
    val developerOptions: Boolean = false,
    val debuggingEnabled: Boolean = false,
) {
    val networkDebugging: Boolean get() = port > 0

    /** True when a connection is possible right now. */
    val reachable: Boolean
        get() = address.isNotBlank() && networkDebugging && debuggingEnabled

    /** QR code payload, scanned by the companion. */
    fun uri(): String = "tvslim://connect?host=$address&port=$port"
}

@Singleton
class LocalNetworkInfo @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun contactPoint(): ContactPoint = ContactPoint(
        address = localAddress().orEmpty(),
        port = adbPort() ?: 0,
        developerOptions = isSettingEnabled(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
        debuggingEnabled = isSettingEnabled(Settings.Global.ADB_ENABLED),
    )

    /** First non-loopback, non-link-local IPv4 address of the active network, as the phone sees it. */
    private fun localAddress(): String? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork ?: return@runCatching null
        manager.getLinkProperties(network)
            ?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /**
     * ADB's TCP port from `service.adb.tcp.port`, which `getprop` exposes to any app. Empty until
     * network debugging is enabled.
     */
    private fun adbPort(): Int? = runCatching {
        val process = ProcessBuilder("getprop", PORT_PROPERTY)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        output.toIntOrNull()?.takeIf { it in 1..65535 }
    }.getOrNull()

    private fun isSettingEnabled(key: String): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, key, 0) == 1
    }.getOrDefault(false)

    private companion object {
        const val PORT_PROPERTY = "service.adb.tcp.port"
    }
}
