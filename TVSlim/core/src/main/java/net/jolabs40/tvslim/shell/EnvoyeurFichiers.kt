package net.jolabs40.tvslim.shell

import java.io.InputStream

/**
 * Écriture d'un fichier sur le téléviseur, comme `adb push` : par le protocole de synchronisation d'ADB, et
 * non par le shell — un film de plusieurs gigaoctets n'entre pas dans une ligne de commande.
 *
 * À part d'[ExecuteurCommande] pour la même raison qu'[InstallateurApk] : un envoi ne se rejoue pas dans le
 * dos de la personne. La connexion ADB de chaque application implémente les trois.
 */
interface EnvoyeurFichiers {
    /**
     * Écrit [source] dans [chemin], qui est remplacé s'il existe. [source] est refermé ensuite, quoi qu'il
     * arrive. [taille] ne sert qu'à suivre l'envoi — 0 quand on l'ignore — et [date] est en millisecondes.
     * [annule] est consulté à chaque bloc : vrai, l'envoi s'arrête là.
     *
     * Renvoie le code 0 quand le téléviseur a tout reçu ; son refus en code 1, tel qu'il l'a écrit
     * (`couldn't create file: Permission denied`) ; [ResultatShell.indisponible] quand la connexion a lâché,
     * ou que l'envoi a été annulé.
     */
    suspend fun envoyer(
        source: InputStream,
        taille: Long,
        chemin: String,
        date: Long,
        annule: () -> Boolean,
        surEnvoi: (envoye: Long) -> Unit,
    ): ResultatShell
}
