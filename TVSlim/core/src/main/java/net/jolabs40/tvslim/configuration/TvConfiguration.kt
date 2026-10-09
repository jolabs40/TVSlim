package net.jolabs40.tvslim.configuration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import java.time.LocalDate

/**
 * A TV's configuration, saved to be reapplied later: after a factory reset, after an update that
 * re-enabled everything, or on a second device of the same model.
 *
 * Holds only what TV Slim can restore (catalogue package states and the home screen), as readable JSON
 * that the Windows app and the Android companion write and read identically.
 */
@Serializable
data class TvConfiguration(
    /** Always [APPLICATION]; any other JSON file is not a configuration. */
    val application: String,
    /** Format version. A file newer than the app is rejected rather than misread. */
    val format: Int,
    /** Save time, in milliseconds since the Unix epoch. */
    @SerialName("sauvegardeLe") val savedAt: Long,
    @SerialName("appareil") val device: SavedDevice = SavedDevice(),
    /** Home screen at save time. */
    @SerialName("accueil") val home: SavedHome? = null,
    /** Catalogue packages disabled at save time. */
    @SerialName("desactives") val disabled: List<String> = emptyList(),
    /** Catalogue packages present and enabled; re-enabled if they no longer are. */
    @SerialName("actifs") val active: List<String> = emptyList(),
) {
    companion object {
        const val APPLICATION = "TV Slim"
        const val FORMAT = 1
    }
}

/** Source device of the backup, only shown before reapplying. */
@Serializable
data class SavedDevice(
    @SerialName("nom") val name: String = "",
    @SerialName("versionAndroid") val androidVersion: String = "",
)

@Serializable
data class SavedHome(
    @SerialName("paquet") val packageName: String,
    @SerialName("composant") val component: String = "",
    /** Display name at save time, so a launcher missing on the target can still be named. */
    @SerialName("nom") val name: String = "",
)

/** Builds the configuration of the TV as just read: every catalogue package, and the home screen. */
fun Catalog.configurationOf(
    info: DeviceInfo,
    states: Map<String, PackageState>,
    now: Long = System.currentTimeMillis(),
): TvConfiguration {
    fun inState(wanted: PackageState) = entries.map { it.packageName }.distinct().filter { states[it] == wanted }

    // "android" is the chooser Android shows when no home screen is set: nothing to restore.
    val home = info.currentHome.takeIf { it.isNotBlank() && it != "android" }?.let { packageName ->
        SavedHome(
            packageName = packageName,
            component = info.homeComponent,
            name = launcherName(packageName) ?: entries.firstOrNull { it.packageName == packageName }?.name.orEmpty(),
        )
    }
    return TvConfiguration(
        application = TvConfiguration.APPLICATION,
        format = TvConfiguration.FORMAT,
        savedAt = now,
        device = SavedDevice(name = info.displayName, androidVersion = info.androidVersion),
        home = home,
        disabled = inState(PackageState.DISABLED),
        active = inState(PackageState.ACTIVE),
    )
}

/** Writes and reads a [TvConfiguration]. The file format is defined here and nowhere else. */
object ConfigurationFile {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Lets a future version add fields without making its files unreadable here.
        ignoreUnknownKeys = true
    }

    fun write(configuration: TvConfiguration): String =
        json.encodeToString(TvConfiguration.serializer(), configuration)

    /** Parses [text], or returns null if it is not a configuration this version can read. */
    fun read(text: String): TvConfiguration? =
        runCatching { json.decodeFromString(TvConfiguration.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.application == TvConfiguration.APPLICATION && it.format in 1..TvConfiguration.FORMAT }

    /** Suggested file name such as `TVSlim-TCL-Smart-TV-Pro-2026-09-13.json`: device and date, filename-safe. */
    fun suggestedName(info: DeviceInfo, day: LocalDate = LocalDate.now()): String =
        "TVSlim-${info.fileSafeName}-$day.json"
}
