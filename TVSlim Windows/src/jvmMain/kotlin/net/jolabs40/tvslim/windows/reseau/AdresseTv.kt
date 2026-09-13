package net.jolabs40.tvslim.windows.reseau

import net.jolabs40.tvslim.windows.adb.PORT_ADB_PAR_DEFAUT
import java.net.URI
import java.net.URLDecoder

/** Adresse d'un téléviseur. */
data class AdresseTv(val hote: String, val port: Int)

const val SCHEMA_APPAIRAGE = "tvslim://"

private const val PORT_MAXIMUM = 65_535

/**
 * Ce que la personne a saisi, champ par champ ou d'un bloc.
 *
 * Le champ d'adresse accepte aussi « 192.168.1.20:5555 » tel qu'on le recopie depuis l'écran du
 * téléviseur, et le lien `tvslim://connect?…` que montre l'application TV quand elle est
 * installée — elle ne l'est pas forcément, rien ici n'en dépend.
 *
 * Une adresse tapée n'est pas filtrée : la taper est un acte délibéré. Le lien, lui, passe par
 * [lireCodeAppairage] et son filtre, parce qu'il se colle sans se lire.
 */
fun interpreterSaisie(hote: String, port: String): AdresseTv? {
    val brut = hote.trim()
    if (brut.isEmpty()) return null
    if (brut.startsWith(SCHEMA_APPAIRAGE)) return lireCodeAppairage(brut)

    // Une seule fois « : » : une adresse IPv6 en porte plusieurs, et garde le port du champ.
    if (brut.count { it == ':' } == 1) {
        val hoteColle = brut.substringBefore(':').trim()
        val portColle = brut.substringAfter(':').trim().toIntOrNull()
        if (hoteColle.isEmpty() || portColle == null || portColle !in 1..PORT_MAXIMUM) return null
        return AdresseTv(hoteColle, portColle)
    }
    return AdresseTv(brut, port.toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)
}

/**
 * Lit un code d'appairage. Trois formes, comme sur le téléphone : l'URI
 * `tvslim://connect?host=…&port=…`, une adresse `hôte:port`, ou une adresse seule.
 *
 * Ce contenu se colle sans se relire : il est traité comme une entrée extérieure. L'adresse doit
 * être une IPv4 du réseau local — voir [estSurLeReseauLocal] — et le port un port réel.
 */
fun lireCodeAppairage(valeur: String): AdresseTv? {
    val brut = valeur.trim()
    val (hote, port) = when {
        brut.startsWith(SCHEMA_APPAIRAGE) -> {
            val parametres = parametresDe(brut)
            parametres["host"].orEmpty() to
                (parametres["port"]?.toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)
        }

        brut.count { it == ':' } == 1 ->
            brut.substringBefore(':') to
                (brut.substringAfter(':').toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)

        else -> brut to PORT_ADB_PAR_DEFAUT
    }
    if (!estSurLeReseauLocal(hote) || port !in 1..PORT_MAXIMUM) return null
    return AdresseTv(hote, port)
}

private fun parametresDe(lien: String): Map<String, String> {
    val requete = runCatching { URI(lien).rawQuery }.getOrNull() ?: return emptyMap()
    return requete.split('&')
        .mapNotNull { paire ->
            val morceaux = paire.split('=', limit = 2)
            if (morceaux.size != 2) return@mapNotNull null
            morceaux[0] to URLDecoder.decode(morceaux[1], Charsets.UTF_8)
        }
        .toMap()
}

/**
 * Vrai pour une IPv4 des plages privées, de lien-local ou de boucle locale.
 *
 * Sert au lien d'appairage et au balayage du réseau : ni l'un ni l'autre ne doit faire partir un
 * handshake ADB — et la clé publique de l'application avec — vers un hôte que personne n'a choisi.
 */
fun estSurLeReseauLocal(hote: String): Boolean {
    val octets = IPV4.matchEntire(hote)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
    if (octets.any { it > 255 }) return false
    return when {
        octets[0] == 10 -> true                         // 10.0.0.0/8
        octets[0] == 172 && octets[1] in 16..31 -> true // 172.16.0.0/12
        octets[0] == 192 && octets[1] == 168 -> true    // 192.168.0.0/16
        octets[0] == 169 && octets[1] == 254 -> true    // 169.254.0.0/16, lien-local
        octets[0] == 127 -> true                        // boucle locale, pour un émulateur
        else -> false
    }
}

/**
 * Un nom de fichier tiré d'une adresse, et rien d'autre : ni `/` ni `\` ne peuvent ouvrir de
 * sous-dossier. Même règle que le compagnon — `192.168.2.135` devient `192_168_2_135`.
 */
fun cleDeFichier(hote: String): String =
    hote.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
