package net.jolabs40.tvslim.device

import java.net.URLEncoder

/**
 * Proposer au catalogue ce qu'il ignore : l'inventaire exporté, joint à une issue GitHub ouverte sur le
 * modèle `nouvel-appareil.yml`. Rien ne part tout seul — la personne voit le formulaire, y joint le fichier
 * et l'envoie elle-même, depuis son propre compte.
 */
object PropositionCatalogue {

    /** Le dépôt de TV Slim. La version Windows passe le sien, tiré de sa configuration de build. */
    const val DEPOT = "jolabs40/TVSlim"

    /** Le modèle d'issue, dans `.github/ISSUE_TEMPLATE` à la racine du dépôt. */
    const val MODELE = "nouvel-appareil.yml"

    /**
     * Proposé dès qu'un paquet du constructeur échappe au catalogue, quelle que soit la marque : c'est là
     * qu'est ce qu'on vient chercher. Les inconnus d'Android — surcouches, modules APEX — n'en valent pas
     * la peine, et un appareil déjà décrit n'a plus rien à proposer.
     */
    fun aProposer(inconnus: List<PaquetInconnu>): Boolean = inconnus.any { it.origine == OriginePaquet.CONSTRUCTEUR }

    /** Le formulaire, titre et appareil déjà remplis : `device` est l'identifiant du champ dans le modèle. */
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

    /** `URLEncoder` écrit l'espace « + », que GitHub garderait tel quel dans un champ : « %20 » partout. */
    private fun encoder(texte: String): String = URLEncoder.encode(texte, "UTF-8").replace("+", "%20")
}
