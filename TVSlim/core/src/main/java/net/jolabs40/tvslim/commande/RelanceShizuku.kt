package net.jolabs40.tvslim.commande

/**
 * Relancer le service Shizuku sur le téléviseur, depuis le téléphone.
 *
 * ⚠️ **Ce n'est pas un retour en arrière sur le § 1.** Shizuku a été écarté
 * comme *canal de privilèges* de TV Slim — le compagnon ADB fait mieux, et son
 * autorisation survit aux redémarrages. Ici, Shizuku n'est pas notre canal :
 * c'est un service que **d'autres** applications du téléviseur utilisent, et
 * qui meurt à chaque extinction. Seul adb le ressuscite, ce qui voulait dire
 * brancher un ordinateur. TV Slim en a déjà un sous la main.
 *
 * Le cas d'usage qui l'a demandé : le gestionnaire des tâches de StartLight, qui
 * lit les processus par Shizuku faute de pouvoir obtenir `DUMP` sur le Play
 * Store, et se rendort donc à chaque redémarrage du téléviseur.
 *
 * ⚠️ **Le lanceur natif, jamais `start.sh`** (§ 5, éprouvé le 2026-09-02 puis le
 * 2026-09-20). Les deux méthodes couramment citées échouent : `start.sh` n'existe
 * pas tant que l'interface de Shizuku n'a jamais été ouverte — le cas d'un
 * téléviseur neuf —, et `app_process … moe.shizuku.server.Shizuku` lève
 * `ClassNotFoundException` depuis Shizuku 13.
 *
 * ⚠️ **Le chemin se découvre, il ne s'écrit pas.** Le dossier d'installation
 * porte un suffixe tiré au sort à chaque mise à jour, et l'ABI n'est pas
 * toujours `arm` : `pm path` donne l'un, le motif donne l'autre. Une commande à
 * chemin figé marcherait sur la TCL et nulle part ailleurs.
 */
object RelanceShizuku {

    /** Le paquet du service, le même sur tous les appareils. */
    const val PAQUET = "moe.shizuku.privileged.api"

    /**
     * La commande envoyée au téléviseur.
     *
     * Elle répond `info: shizuku_server pid is <n>` puis `exit with 0`, et le
     * serveur reste en vie après la fermeture de la session — contrairement à ce
     * qu'un `nohup … &` laisserait croire nécessaire.
     */
    const val COMMANDE: String =
        "p=\$(pm path $PAQUET | sed 's/package://;s|/base.apk||'); \"\$p\"/lib/*/libshizuku.so"

    /**
     * Le service tourne-t-il, au vu de la sortie de `ps` ?
     *
     * ⚠️ **Jamais `pkill -f` pour l'arrêter, ni `ps | grep` d'une commande qui se
     * contient elle-même** : le shell qui porte le motif se trouve lui-même, et
     * `pkill -f` se tue avant d'agir (relevé le 2026-09-20).
     */
    const val COMMANDE_ETAT: String = "ps -A -o USER,PID,NAME | grep shizuku_server"

    /** Vrai quand la sortie du starter annonce un serveur démarré. */
    fun demarre(sortie: String): Boolean =
        sortie.contains("shizuku_server pid is") || sortie.contains("shizuku_starter exit with 0")
}
