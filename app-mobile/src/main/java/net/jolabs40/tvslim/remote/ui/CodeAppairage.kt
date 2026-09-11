package net.jolabs40.tvslim.remote.ui

import android.net.Uri
import net.jolabs40.tvslim.remote.adb.PORT_ADB_PAR_DEFAUT

/** Adresse d'un téléviseur, telle qu'un code scanné ou une saisie la porte. */
data class AdresseTv(val hote: String, val port: Int)

const val SCHEMA_APPAIRAGE = "tvslim://"

/**
 * Lit ce que le code affiché par le téléviseur contient. Trois formes acceptées : l'URI
 * `tvslim://connect?host=…&port=…` que produit l'application du téléviseur, une adresse
 * `hôte:port`, ou une adresse seule.
 *
 * Renvoie `null` quand rien d'exploitable n'en sort — à l'appelant de le dire.
 */
fun lireCodeAppairage(valeur: String): AdresseTv? {
    val brut = valeur.trim()
    val (hote, port) = when {
        brut.startsWith(SCHEMA_APPAIRAGE) -> {
            val uri = Uri.parse(brut)
            uri.getQueryParameter("host").orEmpty() to
                (uri.getQueryParameter("port")?.toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)
        }

        brut.count { it == ':' } == 1 ->
            brut.substringBefore(':') to
                (brut.substringAfter(':').toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)

        else -> brut to PORT_ADB_PAR_DEFAUT
    }
    if (hote.isBlank() || hote.any { it.isWhitespace() }) return null
    return AdresseTv(hote, port)
}
