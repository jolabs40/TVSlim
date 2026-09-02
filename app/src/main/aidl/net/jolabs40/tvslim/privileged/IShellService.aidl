// Contrat du service privilégié exécuté par Shizuku dans un process en UID shell (2000).
package net.jolabs40.tvslim.privileged;

interface IShellService {
    // Exécute une commande shell et renvoie « code de sortie \n sortie fusionnée ».
    String executer(String commande) = 1;

    // Identifiant imposé par le serveur Shizuku pour l'arrêt du service utilisateur.
    void destroy() = 16777114;
}
