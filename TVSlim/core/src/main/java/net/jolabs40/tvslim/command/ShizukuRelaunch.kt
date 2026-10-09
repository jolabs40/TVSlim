package net.jolabs40.tvslim.command

/**
 * Restarts the Shizuku service on the TV from the phone.
 *
 * Shizuku is not TV Slim's privilege channel (the ADB companion is, and its
 * authorization survives reboots). Other apps on the TV use Shizuku, such as
 * StartLight's task manager, which reads processes through it because a Play
 * Store app cannot get `DUMP`. The service dies on every shutdown and only adb
 * can restart it, which TV Slim already has.
 *
 * Uses the native starter, never `start.sh`. Both commonly cited methods fail:
 * `start.sh` does not exist until Shizuku's UI has been opened once (a new TV),
 * and `app_process ... moe.shizuku.server.Shizuku` throws
 * `ClassNotFoundException` on Shizuku 13 and later.
 *
 * The path is looked up, never hardcoded: the install folder gets a random
 * suffix on every update and the ABI is not always `arm`. `pm path` gives the
 * former, the glob covers the latter.
 */
object ShizukuRelaunch {

    const val PACKAGE_NAME = "moe.shizuku.privileged.api"

    /**
     * Prints `info: shizuku_server pid is <n>` then `exit with 0`. The server
     * outlives the session; no `nohup ... &` is needed.
     */
    const val COMMAND: String =
        "p=\$(pm path $PACKAGE_NAME | sed 's/package://;s|/base.apk||'); \"\$p\"/lib/*/libshizuku.so"

    /**
     * Checks from `ps` output whether the service is running.
     *
     * Never use `pkill -f` to stop it, nor `ps | grep` on full command lines:
     * the shell carrying the pattern matches itself, and `pkill -f` kills
     * itself before acting.
     */
    const val STATE_COMMAND: String = "ps -A -o USER,PID,NAME | grep shizuku_server"

    /** True when the starter output reports a started server. */
    fun started(output: String): Boolean =
        output.contains("shizuku_server pid is") || output.contains("shizuku_starter exit with 0")
}
