package net.jolabs40.tvslim.shell

import java.io.OutputStream

/**
 * Lecture d'un fichier du téléviseur, comme `adb pull` : par le protocole de synchronisation d'ADB, la voie
 * d'[EnvoyeurFichiers] dans l'autre sens.
 *
 * À part d'[ExecuteurCommande] pour la même raison : une copie de plusieurs gigaoctets ne se rejoue pas dans le
 * dos de la personne. Seule la connexion de Windows l'implémente — copier vers le téléphone n'a pas été demandé.
 */
interface RecepteurFichiers {
    /**
     * Écrit le contenu de [chemin] dans [destination], que referme l'appelant. [taille] ne sert qu'à suivre la
     * copie — 0 quand on l'ignore. [annule] est consulté à chaque bloc : vrai, la copie s'arrête là.
     *
     * Renvoie le code 0 quand tout est arrivé ; le refus du téléviseur en code 1, tel qu'il l'a écrit
     * (`open failed: Permission denied`) ; [ResultatShell.indisponible] quand la connexion a lâché, ou que la
     * copie a été annulée — [destination] n'a alors reçu qu'une partie du fichier.
     */
    suspend fun recevoir(
        chemin: String,
        destination: OutputStream,
        taille: Long,
        annule: () -> Boolean,
        surRecu: (recu: Long) -> Unit,
    ): ResultatShell
}
