package net.jolabs40.tvslim.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Risk level shown next to each package. */
@Serializable
enum class Risk {
    @SerialName("aucun")
    NONE,

    @SerialName("faible")
    LOW,

    @SerialName("moyen")
    MEDIUM,

    @SerialName("eleve")
    HIGH,
}

@Serializable
data class PackageEntry(
    @SerialName("paquet") val packageName: String,
    @SerialName("nom") val name: String,
    val description: String,
    @SerialName("categorie") val category: String,
    @SerialName("risque") val risk: Risk = Risk.LOW,
    @SerialName("marque") val brand: String = "",
    @SerialName("effetDeBord") val sideEffect: String? = null,
    @SerialName("tailleMo") val sizeMb: Int? = null,
    /** Order within a batch; lower values run first. */
    @SerialName("ordre") val order: Int = 100,
    /** True for home screen packages, which require a third-party launcher to be installed. */
    @SerialName("requiertLauncherTiers") val requiresThirdPartyLauncher: Boolean = false,
    /**
     * False for an entry described from a submitted inventory, never disabled on a real device: it can be
     * disabled individually but is never checked by a profile.
     */
    @SerialName("eprouve") val tested: Boolean = true,
)

@Serializable
data class Category(
    val id: String,
    @SerialName("nom") val name: String,
)

@Serializable
data class Profile(
    val id: String,
    @SerialName("nom") val name: String,
    val description: String,
    val categories: List<String> = emptyList(),
)

@Serializable
data class ProtectedPackage(
    @SerialName("paquet") val packageName: String,
    @SerialName("raison") val protectionReason: String,
)

@Serializable
data class SystemSetting(
    @SerialName("cle") val key: String,
    @SerialName("portee") val scope: String,
    @SerialName("nom") val name: String,
    val description: String,
    @SerialName("valeurOptimisee") val optimizedValue: String,
    @SerialName("valeurDefaut") val defaultValue: String,
    @SerialName("reappliquerAuDemarrage") val reapplyOnBoot: Boolean = false,
)

/**
 * Replacement launcher suggested by TV Slim. Without a third-party launcher the safeguard refuses
 * to disable the stock home screen; this entry offers the way out.
 */
@Serializable
data class RecommendedLauncher(
    @SerialName("paquet") val packageName: String,
    @SerialName("nom") val name: String,
    val description: String,
    /** Stable ID that each app maps to its logo. */
    val id: String = "",
    /** Other packages of the same launcher, such as its debug build. */
    @SerialName("variantes") val variants: List<String> = emptyList(),
    @SerialName("pointsForts") val highlights: List<String> = emptyList(),
    /**
     * False until the launcher is on the Play Store: with no store page to open on the TV, the
     * button says so instead of failing.
     */
    @SerialName("disponible") val available: Boolean = true,
    /** Website, offered while the launcher is not installed. */
    val site: String = "",
) {
    fun matches(installedPackage: String): Boolean =
        installedPackage == packageName || installedPackage in variants

    /** Website without the scheme, e.g. `startlightlauncher.com`. */
    val displayedSite: String get() = site.substringAfter("://").trimEnd('/')
}

/** A widespread third-party launcher: named and shown with its logo when installed, never suggested. */
@Serializable
data class KnownLauncher(
    val id: String,
    @SerialName("nom") val name: String,
    @SerialName("paquets") val packages: List<String>,
)

@Serializable
data class Catalog(
    val version: Int = 0,
    val source: String = "",
    @SerialName("profils") val profiles: List<Profile> = emptyList(),
    val categories: List<Category> = emptyList(),
    @SerialName("entrees") val entries: List<PackageEntry> = emptyList(),
    @SerialName("proteges") val protectedPackages: List<ProtectedPackage> = emptyList(),
    @SerialName("reglages") val settings: List<SystemSetting> = emptyList(),
    val launchers: List<RecommendedLauncher> = emptyList(),
    @SerialName("launchersConnus") val knownLaunchers: List<KnownLauncher> = emptyList(),
) {
    private val protectedByPackage: Map<String, ProtectedPackage> by lazy {
        protectedPackages.associateBy { it.packageName }
    }

    fun isProtected(packageName: String): Boolean = protectedByPackage.containsKey(packageName)

    fun protectionReason(packageName: String): String? = protectedByPackage[packageName]?.protectionReason

    fun categoryName(id: String): String = categories.firstOrNull { it.id == id }?.name ?: id

    /** Entries in the profile's categories, tested ones only. */
    fun profileEntries(profile: Profile): List<PackageEntry> =
        entries.filter { it.category in profile.categories && it.tested }

    /** The recommended launcher that [packageName] is a variant of, if any. */
    fun recommendedLauncher(packageName: String): RecommendedLauncher? =
        launchers.firstOrNull { it.matches(packageName) }

    /** Brand name of an installed launcher, when known. */
    fun launcherName(packageName: String): String? =
        recommendedLauncher(packageName)?.name ?: knownLaunchers.firstOrNull { packageName in it.packages }?.name

    /** Logo ID of an installed launcher, when there is one. */
    fun launcherId(packageName: String): String? =
        recommendedLauncher(packageName)?.id?.takeIf { it.isNotBlank() }
            ?: knownLaunchers.firstOrNull { packageName in it.packages }?.id

    /** Recommended launchers still worth suggesting: none of their variants (debug build included) is installed. */
    fun launchersToOffer(installed: Collection<String>): List<RecommendedLauncher> =
        launchers.filterNot { recommended -> installed.any(recommended::matches) }

    /**
     * Sorts installed launchers with TV Slim's recommendations first (release before debug build), then
     * the others in their original order.
     */
    fun <T> recommendedFirst(installed: List<T>, packageName: (T) -> String): List<T> =
        installed.sortedBy { element ->
            val recommended = recommendedLauncher(packageName(element))
            when {
                recommended == null -> 2
                recommended.packageName == packageName(element) -> 0
                else -> 1
            }
        }
}
