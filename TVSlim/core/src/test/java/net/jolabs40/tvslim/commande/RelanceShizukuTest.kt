package net.jolabs40.tvslim.commande

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La relance du service Shizuku : ce qui part, et comment on sait que c'est parti.
 *
 * **Pourquoi ces tests existent.** La commande traverse une socket ADB et un shell distant : si
 * elle est mal formée, elle échoue sur le téléviseur, loin d'ici, avec un message que personne ne
 * lit. Sa forme est donc tenue ici.
 */
class RelanceShizukuTest {

    /**
     * ⚠️ **Le chemin se découvre, il ne s'écrit pas.** Le dossier d'installation porte un suffixe
     * tiré au sort à chaque mise à jour, et l'ABI n'est pas toujours `arm` : une commande à chemin
     * figé marcherait sur une machine et sur aucune autre.
     */
    @Test
    fun `la commande decouvre le chemin au lieu de le figer`() {
        val commande = RelanceShizuku.COMMANDE

        assertTrue(commande.contains("pm path ${RelanceShizuku.PAQUET}"))
        assertTrue("l'ABI doit rester un motif", commande.contains("/lib/*/libshizuku.so"))
        assertFalse("aucun chemin d'installation en dur", commande.contains("/data/app/"))
        assertFalse("aucune ABI figée", commande.contains("/lib/arm/"))
    }

    /**
     * ⚠️ **Ni `start.sh`, ni `app_process`** (§ 5) : le premier n'existe pas tant que l'interface
     * de Shizuku n'a jamais été ouverte — le cas d'un téléviseur neuf —, le second lève
     * `ClassNotFoundException` depuis Shizuku 13.
     */
    @Test
    fun `la commande n'emprunte aucune des deux voies qui echouent`() {
        assertFalse(RelanceShizuku.COMMANDE.contains("start.sh"))
        assertFalse(RelanceShizuku.COMMANDE.contains("app_process"))
    }

    /**
     * ⚠️ **Le code de retour ne dit pas que c'est parti.** Le starter rend 0 même quand il
     * renonce : c'est sa sortie qui annonce le pid.
     */
    @Test
    fun `le demarrage se lit dans la sortie, pas dans le code`() {
        assertTrue(RelanceShizuku.demarre("info: shizuku_server pid is 5276"))
        assertTrue(RelanceShizuku.demarre("info: shizuku_starter exit with 0"))
        assertFalse(RelanceShizuku.demarre(""))
        assertFalse(RelanceShizuku.demarre("info: starter begin"))
        assertFalse(RelanceShizuku.demarre("/system/bin/sh: not found"))
    }

    /**
     * ⚠️ **Le motif d'état ne doit pas se trouver lui-même.** Un `ps | grep` dont le shell porte
     * le motif se voit dans sa propre sortie, et `pkill -f` se tue avant d'agir — relevé le
     * 2026-09-20, l'appel était resté pendu.
     */
    @Test
    fun `l'etat se lit sans pkill`() {
        assertFalse(RelanceShizuku.COMMANDE_ETAT.contains("pkill"))
        assertTrue(RelanceShizuku.COMMANDE_ETAT.startsWith("ps "))
    }
}
