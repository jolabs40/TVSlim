package net.jolabs40.tvslim.windows.mcp

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.windows.AppInfo
import net.jolabs40.tvslim.windows.Locations
import net.jolabs40.tvslim.windows.adb.AdbClient
import net.jolabs40.tvslim.windows.adb.AdbKeyStore
import net.jolabs40.tvslim.windows.data.WindowsPreferences
import net.jolabs40.tvslim.windows.network.TvDiscovery
import net.jolabs40.tvslim.windows.tools.AppLog
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

private const val TAG = "Mcp"

/**
 * `TV Slim.exe --mcp`: no window, an MCP server on stdin and stdout until the client closes stdin.
 *
 * It uses the app's ADB key, so a TV that already trusts TV Slim on this PC asks nothing, and its own trace file,
 * since the app may be running at the same time.
 */
fun runMcpServer(): Nothing {
    // Only protocol messages may reach stdout: whatever a library prints goes to stderr instead.
    val protocol = FileOutputStream(FileDescriptor.out)
    System.setOut(System.err)

    val locations = Locations.windows()
    AppLog.writeTo(File(locations.data, "traces-mcp.log"))
    AppLog.info(TAG, "Starting the MCP server, TV Slim ${AppInfo.VERSION}")

    val client = AdbClient(AdbKeyStore(locations.keys))
    val tools = TvSlimTools(
        link = AdbTvLink(client),
        catalogs = CatalogRepository(language = { "en" }),
        preferences = WindowsPreferences(locations.preferences),
        discoveries = TvDiscovery()::stream,
        journals = locations.journals,
    )
    val server = McpServer(
        name = "tvslim",
        version = AppInfo.VERSION,
        instructions = TvSlimTools.INSTRUCTIONS,
        tools = tools.all,
        onRequest = { method, modern -> AppLog.info(TAG, "$method (${if (modern) "modern" else "legacy"})") },
    )
    try {
        runBlocking { serveStdio(server, System.`in`, protocol) }
    } catch (e: Exception) {
        AppLog.warn(TAG, "MCP server stopped on an error", e)
    } finally {
        client.disconnect()
    }
    AppLog.info(TAG, "MCP server stopped")
    // Discovery and ADB threads would otherwise keep the process alive after the client is gone.
    exitProcess(0)
}
