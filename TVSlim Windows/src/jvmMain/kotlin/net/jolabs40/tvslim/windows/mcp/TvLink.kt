package net.jolabs40.tvslim.windows.mcp

import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.windows.adb.AdbClient
import net.jolabs40.tvslim.windows.adb.ConnectionProblem
import net.jolabs40.tvslim.windows.adb.ConnectionState
import net.jolabs40.tvslim.windows.network.TvAddress

/** The ADB connection the tools read through: [AdbClient] in the app, a fake in tests. */
interface TvLink {
    val executor: CommandExecutor

    fun isConnectedTo(address: TvAddress): Boolean

    /** Waits for the TV to authorize this PC; null once connected, otherwise why not. */
    suspend fun connect(address: TvAddress): ConnectionProblem?

    fun disconnect()
}

class AdbTvLink(private val client: AdbClient) : TvLink {
    override val executor: CommandExecutor get() = client

    override fun isConnectedTo(address: TvAddress): Boolean = client.connection.value.let {
        it.state == ConnectionState.CONNECTED && it.host == address.host && it.port == address.port
    }

    override suspend fun connect(address: TvAddress): ConnectionProblem? =
        if (client.connect(address.host, address.port)) null else client.connection.value.problem ?: ConnectionProblem.OTHER

    override fun disconnect() = client.disconnect()
}
