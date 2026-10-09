package net.jolabs40.tvslim.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Catalogue translations.
 *
 * `catalog.json` holds the structure and the English text (the default language, like `values/`).
 * Each other language only adds an override file keyed by ID (`catalog-fr.json`), so adding a
 * package only touches the base file and a missing translation falls back to English.
 */
@Serializable
data class EntryText(
    @SerialName("nom") val name: String? = null,
    val description: String? = null,
    @SerialName("effetDeBord") val sideEffect: String? = null,
)

@Serializable
data class NamedText(
    @SerialName("nom") val name: String? = null,
    val description: String? = null,
)

/** Text of a recommended launcher. Its highlights are only replaced as a whole list. */
@Serializable
data class LauncherText(
    @SerialName("nom") val name: String? = null,
    val description: String? = null,
    @SerialName("pointsForts") val highlights: List<String>? = null,
)

@Serializable
data class Translations(
    @SerialName("langue") val language: String = "",
    val source: String? = null,
    val categories: Map<String, String> = emptyMap(),
    @SerialName("profils") val profiles: Map<String, NamedText> = emptyMap(),
    @SerialName("entrees") val entries: Map<String, EntryText> = emptyMap(),
    @SerialName("proteges") val protectedPackages: Map<String, String> = emptyMap(),
    @SerialName("reglages") val settings: Map<String, NamedText> = emptyMap(),
    val launchers: Map<String, LauncherText> = emptyMap(),
)

/** Applies a language override field by field; missing fields keep their original value. */
fun Catalog.translated(translations: Translations): Catalog = copy(
    source = translations.source ?: source,
    categories = categories.map { category ->
        translations.categories[category.id]?.let { category.copy(name = it) } ?: category
    },
    profiles = profiles.map { profile ->
        translations.profiles[profile.id]?.let { text ->
            profile.copy(
                name = text.name ?: profile.name,
                description = text.description ?: profile.description,
            )
        } ?: profile
    },
    entries = entries.map { entry ->
        translations.entries[entry.packageName]?.let { text ->
            entry.copy(
                name = text.name ?: entry.name,
                description = text.description ?: entry.description,
                sideEffect = text.sideEffect ?: entry.sideEffect,
            )
        } ?: entry
    },
    protectedPackages = protectedPackages.map { protectedItem ->
        translations.protectedPackages[protectedItem.packageName]?.let { protectedItem.copy(protectionReason = it) } ?: protectedItem
    },
    launchers = launchers.map { launcher ->
        translations.launchers[launcher.packageName]?.let { text ->
            launcher.copy(
                name = text.name ?: launcher.name,
                description = text.description ?: launcher.description,
                // A list of a different length would mix two languages on the same card.
                highlights = text.highlights
                    ?.takeIf { it.size == launcher.highlights.size }
                    ?: launcher.highlights,
            )
        } ?: launcher
    },
    settings = settings.map { setting ->
        translations.settings[setting.key]?.let { text ->
            setting.copy(
                name = text.name ?: setting.name,
                description = text.description ?: setting.description,
            )
        } ?: setting
    },
)
