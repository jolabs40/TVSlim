package net.jolabs40.tvslim.device

import java.net.URLEncoder

/**
 * Proposes unknown packages for the catalogue: the exported inventory, attached to a GitHub issue opened from
 * the `nouvel-device.yml` template. Nothing is sent automatically; the user sees the form, attaches the file
 * and submits it from their own account.
 */
object CatalogSuggestion {

    /** The Windows build passes its own repository, from its build configuration. */
    const val REPOSITORY = "jolabs40/TVSlim"

    /** Issue template, in `.github/ISSUE_TEMPLATE` at the repository root. */
    const val ISSUE_TEMPLATE = "nouvel-appareil.yml"

    /**
     * Offered as soon as a manufacturer package is missing from the catalogue, whatever the brand. Unknown
     * Android packages (overlays, APEX modules) are not worth it.
     */
    fun shouldOffer(unknowns: List<UnknownPackage>): Boolean = unknowns.any { it.origin == PackageOrigin.MAKER }

    /** The issue form with title and device prefilled; `device` is the field id in the template. */
    fun link(info: DeviceInfo, repository: String = REPOSITORY): String {
        val version = info.androidVersion.takeIf { it.isNotBlank() }?.let { " (Android $it)" }.orEmpty()
        val parameters = listOf(
            "template" to ISSUE_TEMPLATE,
            "title" to "Catalogue: ${info.displayName.ifBlank { "?" }}$version",
            "device" to info.displayName,
        ).filter { it.second.isNotBlank() }
        return "https://github.com/$repository/issues/new?" +
            parameters.joinToString("&") { (key, rawValue) -> "$key=${encoder(rawValue)}" }
    }

    /** `URLEncoder` encodes spaces as `+`, which GitHub would keep literally in a form field; use `%20`. */
    private fun encoder(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
