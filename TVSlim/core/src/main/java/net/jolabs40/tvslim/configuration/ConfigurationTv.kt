package net.jolabs40.tvslim.configuration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import java.time.LocalDate

/**
 * La configuration d'un téléviseur, sauvegardée pour être réinjectée plus tard : après une remise à
 * zéro, une mise à jour qui a tout réactivé, ou sur un second appareil du même modèle.
 *
 * Elle ne retient que ce que TV Slim sait rétablir — l'état des paquets du catalogue et l'écran
 * d'accueil — dans un fichier JSON lisible, que l'application Windows et le compagnon Android écrivent
 * et relisent à l'identique.
 */
@Serializable
data class ConfigurationTv(
    /** Toujours [APPLICATION] : un autre fichier JSON n'est pas une configuration. */
    val application: String,
    /** Version du format. Un fichier plus récent que l'application est refusé plutôt que mal lu. */
    val format: Int,
    /** Instant de la sauvegarde, en millisecondes depuis l'époque Unix. */
    val sauvegardeLe: Long,
    val appareil: AppareilSauvegarde = AppareilSauvegarde(),
    /** L'écran d'accueil en place au moment de la sauvegarde. */
    val accueil: AccueilSauvegarde? = null,
    /** Paquets du catalogue désactivés au moment de la sauvegarde. */
    val desactives: List<String> = emptyList(),
    /** Paquets du catalogue présents et actifs : réactivés s'ils ne le sont plus. */
    val actifs: List<String> = emptyList(),
) {
    companion object {
        const val APPLICATION = "TV Slim"
        const val FORMAT = 1
    }
}

/** L'appareil d'où vient la sauvegarde : pour le dire avant de réinjecter, rien de plus. */
@Serializable
data class AppareilSauvegarde(
    val nom: String = "",
    val versionAndroid: String = "",
)

@Serializable
data class AccueilSauvegarde(
    val paquet: String,
    val composant: String = "",
    /** Son nom lisible au moment de la sauvegarde : un launcher absent se nomme encore. */
    val nom: String = "",
)

/** La configuration du téléviseur tel qu'il vient d'être lu : chaque paquet du catalogue, et l'accueil. */
fun Catalogue.configurationDe(
    infos: InfosAppareil,
    etats: Map<String, EtatPaquet>,
    maintenant: Long = System.currentTimeMillis(),
): ConfigurationTv {
    fun dansLEtat(voulu: EtatPaquet) = entrees.map { it.paquet }.distinct().filter { etats[it] == voulu }

    // « android » est le sélecteur que montre Android quand aucun accueil n'est choisi : rien à rétablir.
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

/** Écrit et relit une [ConfigurationTv] : le format du fichier ne se décide qu'ici. */
object FichierConfiguration {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Une version future pourra ajouter des champs sans rendre ses fichiers illisibles ici.
        ignoreUnknownKeys = true
    }

    fun ecrire(configuration: ConfigurationTv): String =
        json.encodeToString(ConfigurationTv.serializer(), configuration)

    /** La configuration que contient [texte], ou null si ce n'en est pas une que cette version sait lire. */
    fun lire(texte: String): ConfigurationTv? =
        runCatching { json.decodeFromString(ConfigurationTv.serializer(), texte) }
            .getOrNull()
            ?.takeIf { it.application == ConfigurationTv.APPLICATION && it.format in 1..ConfigurationTv.FORMAT }

    /** « TVSlim-TCL-Smart-TV-Pro-2026-09-13.json » : l'appareil et le jour, sans caractère qui gêne. */
    fun nomPropose(infos: InfosAppareil, jour: LocalDate = LocalDate.now()): String {
        val appareil = infos.nomAffiche
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "televiseur" }
        return "TVSlim-$appareil-$jour.json"
    }
}
