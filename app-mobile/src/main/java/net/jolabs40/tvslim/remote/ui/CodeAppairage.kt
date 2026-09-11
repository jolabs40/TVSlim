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
 * Ce contenu vient d'une **image**, et un autocollant collé n'importe où se scanne aussi bien
 * que l'écran d'un téléviseur : il est traité comme une entrée extérieure. L'adresse doit donc
 * être une IPv4 du réseau local — voir [estSurLeReseauLocal] — et le port un port réel.
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
    if (!estSurLeReseauLocal(hote) || port !in 1..PORT_MAXIMUM) return null
    return AdresseTv(hote, port)
}

/**
 * Le téléviseur est sur le réseau local, par construction : `InfosReseau` ne met dans le QR
 * qu'une IPv4 lue sur l'interface active, jamais un nom d'hôte.
 *
 * Sans ce filtre, un code fabriqué — `tvslim://connect?host=exemple.invalide` — ferait partir
 * un handshake ADB, et la clé publique du compagnon avec, vers un hôte que personne n'a choisi ;
 * sa réponse serait ensuite découpée et interprétée par le lecteur d'état. La clé privée ne sort
 * pas, mais le compagnon parlerait tout de même à un inconnu.
 *
 * La saisie manuelle n'y passe pas : y taper une adresse est un acte délibéré, pas le contenu
 * d'une image trouvée sous l'objectif.
 */
internal fun estSurLeReseauLocal(hote: String): Boolean {
    val octets = IPV4.matchEntire(hote)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
    if (octets.any { it > 255 }) return false
    return when {
        octets[0] == 10 -> true                        // 10.0.0.0/8
        octets[0] == 172 && octets[1] in 16..31 -> true // 172.16.0.0/12
        octets[0] == 192 && octets[1] == 168 -> true    // 192.168.0.0/16
        octets[0] == 169 && octets[1] == 254 -> true    // 169.254.0.0/16, lien-local
        octets[0] == 127 -> true                        // boucle locale, pour un émulateur
        else -> false
    }
}

/**
 * Un nom de fichier tiré d'une adresse, et rien d'autre.
 *
 * Remplacer les seuls points laissait passer `/` et `\` : un hôte `a/b` écrivait dans
 * `journaux/a/b.json`, le sous-dossier étant créé au passage par `mkdirs()`. L'adresse venant
 * d'un code scanné, c'était l'extérieur qui choisissait où l'application écrit.
 *
 * Une adresse IPv4 donne exactement ce que donnait l'ancienne règle — `192.168.2.135` devient
 * `192_168_2_135` — donc les journaux et les mesures déjà enregistrés restent retrouvés.
 */
fun cleDeFichier(hote: String): String =
    hote.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

private const val PORT_MAXIMUM = 65_535
