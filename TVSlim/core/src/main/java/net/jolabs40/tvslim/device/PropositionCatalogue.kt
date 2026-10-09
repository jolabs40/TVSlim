package net.jolabs40.tvslim.device

import java.net.URLEncoder

/**
 * Proposes unknown packages for the catalogue: the exported inventory, attached to a GitHub issue opened from
 * the `nouvel-appareil.yml` template. Nothing is sent automatically; the user sees the form, attaches the file
 * and submits it from their own account.
 */
object PropositionCatalogue {

    /** The Windows build passes its own repository, from its build configuration. */
    const val DEPOT = "jolabs40/TVSlim"

    /** Issue template, in `.github/ISSUE_TEMPLATE` at the repository root. */
    const val MODELE = "nouvel-appareil.yml"

    /**
     * Offered as soon as a manufacturer package is missing from the catalogue, whatever the brand. Unknown
     * Android packages (overlays, APEX modules) are not worth it.
     */
    fun aProposer(inconnus: List<PaquetInconnu>): Boolean = inconnus.any { it.origine == OriginePaquet.CONSTRUCTEUR }

    /** The issue form with title and device prefilled; `device` is the field id in the template. */
    fun lien(infos: InfosAppareil, depot: String = DEPOT): String {
        val version = infos.versionAndroid.takeIf { it.isNotBlank() }?.let { " (Android $it)" }.orEmpty()
        val parametres = listOf(
            "template" to MODELE,
            "title" to "Catalogue: ${infos.nomAffiche.ifBlank { "?" }}$version",
            "device" to infos.nomAffiche,
        ).filter { it.second.isNotBlank() }
        return "https://github.com/$depot/issues/new?" +
            parametres.joinToString("&") { (cle, valeur) -> "$cle=${encoder(valeur)}" }
    }

    /** `URLEncoder` encodes spaces as `+`, which GitHub would keep literally in a form field; use `%20`. */
    private fun encoder(texte: String): String = URLEncoder.encode(texte, "UTF-8").replace("+", "%20")
}
