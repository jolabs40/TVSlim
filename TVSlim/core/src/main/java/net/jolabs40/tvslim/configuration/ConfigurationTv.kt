package net.jolabs40.tvslim.configuration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import java.time.LocalDate

/**
 * A TV's configuration, saved to be reapplied later: after a factory reset, after an update that
 * re-enabled everything, or on a second device of the same model.
 *
 * Holds only what TV Slim can restore (catalogue package states and the home screen), as readable JSON
 * that the Windows app and the Android companion write and read identically.
 */
@Serializable
data class ConfigurationTv(
    /** Always [APPLICATION]; any other JSON file is not a configuration. */
    val application: String,
    /** Format version. A file newer than the app is rejected rather than misread. */
    val format: Int,
    /** Save time, in milliseconds since the Unix epoch. */
    val sauvegardeLe: Long,
    val appareil: AppareilSauvegarde = AppareilSauvegarde(),
    /** Home screen at save time. */
    val accueil: AccueilSauvegarde? = null,
    /** Catalogue packages disabled at save time. */
    val desactives: List<String> = emptyList(),
    /** Catalogue packages present and enabled; re-enabled if they no longer are. */
    val actifs: List<String> = emptyList(),
) {
    companion object {
        const val APPLICATION = "TV Slim"
        const val FORMAT = 1
    }
}

/** Source device of the backup, only shown before reapplying. */
@Serializable
data class AppareilSauvegarde(
    val nom: String = "",
    val versionAndroid: String = "",
)

@Serializable
data class AccueilSauvegarde(
    val paquet: String,
    val composant: String = "",
    /** Display name at save time, so a launcher missing on the target can still be named. */
    val nom: String = "",
)

/** Builds the configuration of the TV as just read: every catalogue package, and the home screen. */
fun Catalogue.configurationDe(
    infos: InfosAppareil,
    etats: Map<String, EtatPaquet>,
    maintenant: Long = System.currentTimeMillis(),
): ConfigurationTv {
    fun dansLEtat(voulu: EtatPaquet) = entrees.map { it.paquet }.distinct().filter { etats[it] == voulu }

    // "android" is the chooser Android shows when no home screen is set: nothing to restore.
    val accueil = infos.accueilActuel.takeIf { it.isNotBlank() && it != "android" }?.let { paquet ->
        AccueilSauvegarde(
            paquet = paquet,
            composant = infos.composantAccueil,
            nom = nomLauncher(paquet) ?: entrees.firstOrNull { it.paquet == paquet }?.nom.orEmpty(),
        )
    }
    return ConfigurationTv(
        application = ConfigurationTv.APPLICATION,
        format = ConfigurationTv.FORMAT,
        sauvegardeLe = maintenant,
        appareil = AppareilSauvegarde(nom = infos.nomAffiche, versionAndroid = infos.versionAndroid),
        accueil = accueil,
        desactives = dansLEtat(EtatPaquet.DESACTIVE),
        actifs = dansLEtat(EtatPaquet.ACTIF),
    )
}

/** Writes and reads a [ConfigurationTv]. The file format is defined here and nowhere else. */
object FichierConfiguration {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Lets a future version add fields without making its files unreadable here.
        ignoreUnknownKeys = true
    }

    fun ecrire(configuration: ConfigurationTv): String =
        json.encodeToString(ConfigurationTv.serializer(), configuration)

    /** Parses [texte], or returns null if it is not a configuration this version can read. */
    fun lire(texte: String): ConfigurationTv? =
        runCatching { json.decodeFromString(ConfigurationTv.serializer(), texte) }
            .getOrNull()
            ?.takeIf { it.application == ConfigurationTv.APPLICATION && it.format in 1..ConfigurationTv.FORMAT }

    /** Suggested file name such as `TVSlim-TCL-Smart-TV-Pro-2026-09-13.json`: device and date, filename-safe. */
    fun nomPropose(infos: InfosAppareil, jour: LocalDate = LocalDate.now()): String =
        "TVSlim-${infos.nomPourFichier}-$jour.json"
}
