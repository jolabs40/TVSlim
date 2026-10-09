package net.jolabs40.tvslim.command

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restarting the Shizuku service: the command sent, and how a successful start is detected.
 *
 * The command goes through an ADB socket and a remote shell. A malformed one fails on the TV with a message
 * nobody reads, so its shape is checked here.
 */
class ShizukuRelaunchTest {

    /**
     * The install folder gets a random suffix on every update and the ABI is not always `arm`, so the path must
     * be looked up, never hardcoded.
     */
    @Test
    fun `the command looks up the path instead of hardcoding it`() {
        val command = ShizukuRelaunch.COMMAND

        assertTrue(command.contains("pm path ${ShizukuRelaunch.PACKAGE_NAME}"))
        assertTrue("the ABI must stay a wildcard", command.contains("/lib/*/libshizuku.so"))
        assertFalse("no hardcoded install path", command.contains("/data/app/"))
        assertFalse("no fixed ABI", command.contains("/lib/arm/"))
    }

    /**
     * `start.sh` does not exist until the Shizuku UI has been opened once (a fresh TV), and `app_process`
     * throws `ClassNotFoundException` since Shizuku 13.
     */
    @Test
    fun `the command uses neither of the two ways that fail`() {
        assertFalse(ShizukuRelaunch.COMMAND.contains("start.sh"))
        assertFalse(ShizukuRelaunch.COMMAND.contains("app_process"))
    }

    /** The starter exits with 0 even when it gives up; only its output reports the server pid. */
    @Test
    fun `startup is read from the output, not the exit code`() {
        assertTrue(ShizukuRelaunch.started("info: shizuku_server pid is 5276"))
        assertTrue(ShizukuRelaunch.started("info: shizuku_starter exit with 0"))
        assertFalse(ShizukuRelaunch.started(""))
        assertFalse(ShizukuRelaunch.started("info: starter begin"))
        assertFalse(ShizukuRelaunch.started("/system/bin/sh: not found"))
    }

    /**
     * The status pattern must not match its own command line: `ps | grep` sees its own shell, and `pkill -f` kills
     * itself before acting, which left the call hanging.
     */
    @Test
    fun `the status is read without pkill`() {
        assertFalse(ShizukuRelaunch.STATE_COMMAND.contains("pkill"))
        assertTrue(ShizukuRelaunch.STATE_COMMAND.startsWith("ps "))
    }
}
