package net.jolabs40.tvslim.commande

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restarting the Shizuku service: the command sent, and how a successful start is detected.
 *
 * The command goes through an ADB socket and a remote shell. A malformed one fails on the TV with a message
 * nobody reads, so its shape is checked here.
 */
class RelanceShizukuTest {

    /**
     * The install folder gets a random suffix on every update and the ABI is not always `arm`, so the path must
     * be looked up, never hardcoded.
     */
    @Test
    fun `the command looks up the path instead of hardcoding it`() {
        val commande = RelanceShizuku.COMMANDE

        assertTrue(commande.contains("pm path ${RelanceShizuku.PAQUET}"))
        assertTrue("l'ABI doit rester un motif", commande.contains("/lib/*/libshizuku.so"))
        assertFalse("aucun chemin d'installation en dur", commande.contains("/data/app/"))
        assertFalse("aucune ABI figée", commande.contains("/lib/arm/"))
    }

    /**
     * `start.sh` does not exist until the Shizuku UI has been opened once (a fresh TV), and `app_process`
     * throws `ClassNotFoundException` since Shizuku 13.
     */
    @Test
    fun `the command uses neither of the two ways that fail`() {
        assertFalse(RelanceShizuku.COMMANDE.contains("start.sh"))
        assertFalse(RelanceShizuku.COMMANDE.contains("app_process"))
    }

    /** The starter exits with 0 even when it gives up; only its output reports the server pid. */
    @Test
    fun `startup is read from the output, not the exit code`() {
        assertTrue(RelanceShizuku.demarre("info: shizuku_server pid is 5276"))
        assertTrue(RelanceShizuku.demarre("info: shizuku_starter exit with 0"))
        assertFalse(RelanceShizuku.demarre(""))
        assertFalse(RelanceShizuku.demarre("info: starter begin"))
        assertFalse(RelanceShizuku.demarre("/system/bin/sh: not found"))
    }

    /**
     * The status pattern must not match its own command line: `ps | grep` sees its own shell, and `pkill -f` kills
     * itself before acting, which left the call hanging.
     */
    @Test
    fun `the status is read without pkill`() {
        assertFalse(RelanceShizuku.COMMANDE_ETAT.contains("pkill"))
        assertTrue(RelanceShizuku.COMMANDE_ETAT.startsWith("ps "))
    }
}
