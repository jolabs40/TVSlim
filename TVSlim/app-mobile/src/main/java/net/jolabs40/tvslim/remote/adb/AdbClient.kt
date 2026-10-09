package net.jolabs40.tvslim.remote.adb

import android.content.Context
import android.util.Log
import dadb.AdbShellPacket
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.command.AdbConsole
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.shell.FileUploader
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.DirectExecutor
import net.jolabs40.tvslim.shell.ApkInstaller
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.shell.BinaryReader
import net.jolabs40.tvslim.shell.DirectResponse
import net.jolabs40.tvslim.shell.ShellResult
import net.jolabs40.tvslim.shell.BinaryOutput
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState { DISCONNECTED, CONNECTION, CONNECTED, ERROR }

/** Why a connection failed; the UI turns it into a localized explanation. */
enum class ConnectionProblem { REJECTED, TIMEOUT, UNAUTHORIZED, UNREACHABLE, OTHER }

data class ConnectionUi(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val host: String = "",
    val port: Int = DEFAULT_ADB_PORT,
    val problem: ConnectionProblem? = null,
    /** Raw technical message, shown in small print under the explanation. */
    val detail: String = "",
)

const val DEFAULT_ADB_PORT = 5555

/**
 * ADB connection from the phone to a TV in pure Kotlin (dadb): no `adb` binary, no ADB server, no computer.
 *
 * The first connection shows the "Allow debugging?" prompt on the TV. Once accepted, the public key is stored
 * in the TV's `/data/misc/adb/adb_keys`, so the authorization survives reboots (a local privileged service
 * would die at every shutdown). The private key never leaves the phone and is stored encrypted ([AdbKeyStore]).
 *
 * Two dadb pitfalls:
 *  - dadb only opens the socket on the first command. [connect] forces it, so "connected" is not shown
 *    before the TV has accepted anything.
 *  - `withTimeout` does not interrupt a blocked socket read. Timeouts are enforced by [withWatchdog],
 *    which closes the session from another coroutine.
 */
@Singleton
class AdbClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: AdbKeyStore,
) : CommandExecutor, ApkInstaller, DirectExecutor, FileUploader, BinaryReader {

    private val _connection = MutableStateFlow(ConnectionUi())
    val connection: StateFlow<ConnectionUi> = _connection.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var session: Dadb? = null

    /** Last TV the user connected to; reconnects target it. */
    @Volatile
    private var target: Pair<String, Int>? = null

    /** Time of the last failed reconnect, so it is not retried on every command. */
    private var lastRetryFailure = 0L

    /** False for a secondary session ([openSecond]): once broken, it is not reopened. */
    private var retryAllowed = true

    /**
     * Opens the connection and waits for the TV to accept it. [quiet] is for attempts the user did not
     * ask for (returning to the app): failure is common there (TV off) and shows no error.
     */
    suspend fun connect(
        host: String,
        port: Int = DEFAULT_ADB_PORT,
        quiet: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            closeSession()
            _connection.value = ConnectionUi(ConnectionState.CONNECTION, host, port)
            val timeout = if (quiet) RETRY_DELAY_MS else CONNECTION_TIMEOUT_MS
            when (val outcome = open(host, port, timeout)) {
                is Opening.Succeeded -> {
                    session = outcome.session
                    target = host to port
                    lastRetryFailure = 0L
                    _connection.value = ConnectionUi(ConnectionState.CONNECTED, host, port)
                    true
                }

                is Opening.Failed -> {
                    Log.w(TAG, "Connection failed" + detail("$host:$port"), outcome.error)
                    _connection.value = if (quiet) {
                        ConnectionUi(ConnectionState.DISCONNECTED, host, port)
                    } else {
                        ConnectionUi(
                            state = ConnectionState.ERROR,
                            host = host,
                            port = port,
                            problem = outcome.problem,
                            detail = outcome.error.message.orEmpty(),
                        )
                    }
                    false
                }
            }
        }
    }

    /**
     * Opens a second session to the same TV for the long reads prefetched on connect (apps, `dumpsys meminfo`,
     * storage), leaving the main session free for user actions. The key is already authorized, so the TV shows
     * no prompt.
     *
     * It never reconnects by itself; a failed prefetch is redone on demand through the main session. The caller
     * closes it ([disconnect]), which also interrupts a read in progress. Returns null if no TV is connected or
     * it does not answer.
     */
    suspend fun openSecond(): AdbClient? {
        val (host, port) = target ?: return null
        val second = AdbClient(context, keyStore).apply { retryAllowed = false }
        var opened = false
        try {
            opened = second.connect(host, port, quiet = true)
        } finally {
            // If cancelled midway, the connection may still have succeeded: do not leave it open.
            if (!opened) second.disconnect()
        }
        return second.takeIf { opened }
    }

    fun disconnect() {
        // Deliberately outside the lock: closing the socket is what unblocks a pending command,
        // which holds the lock.
        closeSession()
        // Disconnecting is explicit: nothing may reopen the session behind the user's back.
        target = null
        _connection.value = ConnectionUi()
    }

    /**
     * Runs a command, reconnecting once and replaying it if the session dropped (a sleeping TV closes it
     * silently, which only shows on the next command).
     *
     * Replay is safe because every command sent here is idempotent: reads, `pm disable-user`, `pm enable`,
     * `am force-stop`, `settings put`, opening a store page. Non-idempotent commands must not use this path.
     */
    override suspend fun execute(command: String): ShellResult = withContext(Dispatchers.IO) {
        val request = System.currentTimeMillis()
        lock.withLock {
            val acquiredAt = System.currentTimeMillis()
            val result = executeUnderLock(command)
            // A folder read once took over ten seconds and could not be reproduced. Log slow commands to tell
            // whether they wait on the lock, the TV or a reconnect.
            val end = System.currentTimeMillis()
            if (end - request > SLOW_THRESHOLD_MS) {
                Log.w(
                    TAG,
                    "Slow command: ${end - request} ms, including ${acquiredAt - request} ms waiting for the lock, " +
                        "code ${result.code}" + detail(command.take(80)),
                )
            }
            result
        }
    }

    /** One attempt; if the session dropped, one reconnect and one replay. */
    private suspend fun executeUnderLock(command: String): ShellResult =
        when (val first = attempt(command)) {
            is Outcome.Answered -> first.result
            is Outcome.Broken ->
                if (!resume()) {
                    reportLoss(first)
                    ShellResult.unavailable(first.reason)
                } else {
                    when (val second = attempt(command)) {
                        is Outcome.Answered -> second.result
                        is Outcome.Broken -> {
                            reportLoss(second)
                            ShellResult.unavailable(second.reason)
                        }
                    }
                }
        }

    /**
     * Streams an APK to `cmd package install` without copying it to the TV first.
     *
     * Not routed through [execute]: a large upload is never replayed silently. A session that dropped before
     * the upload is reopened; a break during it is reported. The timeout grows with the file size and leaves
     * Android time to verify the package.
     */
    override suspend fun install(apk: File, onSent: (sent: Long, total: Long) -> Unit): ShellResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (session == null) resume()
                val active = session ?: return@withLock ShellResult.unavailable(noSessionReason())
                val total = apk.length()
                val timeout = INSTALL_TIMEOUT_MS + total / BYTES_PER_MB * TIMEOUT_PER_MB_MS

                withWatchdog(active, timeout) {
                    CountingSource(apk.source(), total, onSent).use { source ->
                        active.install(source, total, *OPTIONS_INSTALLATION)
                    }
                }.fold(
                    onSuccess = { outcome ->
                        when (outcome) {
                            is InstallResult.Success -> ShellResult(code = 0, output = "Success")
                            is InstallResult.Failure -> ShellResult(code = 1, output = outcome.reason.trim())
                        }
                    },
                    onFailure = { error ->
                        Log.w(TAG, "Install interrupted" + detail(apk.name), error)
                        closeSession()
                        val loss = loss(error, timedOut = error is TimedOut)
                        reportLoss(loss)
                        ShellResult.unavailable(loss.reason)
                    },
                )
            }
        }

    /**
     * Writes a file to the TV, like `adb push`. Not replayed, like [install]. The timeout applies to
     * inactivity rather than total duration: nothing sent for [IDLE_TIMEOUT_MS] closes the session.
     *
     * Cancelling stops only this file: dadb closes its stream and the session stays open.
     */
    override suspend fun send(
        source: InputStream,
        size: Long,
        path: String,
        date: Long,
        cancelled: () -> Boolean,
        onSent: (sent: Long) -> Unit,
    ): ShellResult = withContext(Dispatchers.IO) {
        source.use { stream ->
            lock.withLock {
                if (session == null) resume()
                val active = session ?: return@withLock ShellResult.unavailable(noSessionReason())
                val activity = AtomicLong(System.currentTimeMillis())

                underWatch(active, activity, IDLE_TIMEOUT_MS) {
                    UploadSource(stream.source(), size, cancelled, activity, onSent).use { loaded ->
                        active.push(loaded, path, FILE_MODE, date.takeIf { it > 0 } ?: System.currentTimeMillis())
                    }
                }.fold(
                    onSuccess = { response ->
                        when (response) {
                            is SyncResult.Success -> ShellResult(code = 0, output = "")
                            is SyncResult.Failure -> ShellResult(code = 1, output = response.reason.trim())
                        }
                    },
                    onFailure = { error ->
                        if (error is UploadCancelled) {
                            ShellResult.unavailable(context.getString(R.string.adb_send_cancelled))
                        } else {
                            Log.w(TAG, "Upload interrupted" + detail(path), error)
                            closeSession()
                            val loss = loss(error, timedOut = error is ProlongedSilence)
                            reportLoss(loss)
                            ShellResult.unavailable(loss.reason)
                        }
                    },
                )
            }
        }
    }

    /**
     * Runs a user-typed command exactly once: unlike [execute], it is never replayed since it may not be
     * idempotent. Output is streamed so that commands that never end (`logcat` without `-d`, `top`) still
     * return what they printed when the timeout cuts them.
     *
     * The timeout closes the session (the only way to stop the read); the next command reopens it silently.
     */
    override suspend fun executeOnce(command: String): DirectResponse = withContext(Dispatchers.IO) {
        lock.withLock {
            if (session == null) resume()
            val active = session
                ?: return@withLock DirectResponse(null, "", Interruption.CONNECTION, noSessionReason())
            val incoming = ByteArrayOutputStream()
            var code: Int? = null

            withWatchdog(active, AdbConsole.MAX_TIMEOUT_S * 1_000L) {
                active.openShell(command).use { stream ->
                    while (code == null) {
                        when (val packet = stream.read()) {
                            is AdbShellPacket.Exit -> code = packet.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                            else -> incoming.write(packet.payload)
                        }
                    }
                }
            }.fold(
                onSuccess = { DirectResponse(code = code, output = textOf(incoming)) },
                onFailure = { error ->
                    Log.w(TAG, "Free-form command interrupted" + detail(command), error)
                    closeSession()
                    if (error is TimedOut) {
                        DirectResponse(null, textOf(incoming), Interruption.TIMEOUT, timeoutReason())
                    } else {
                        val loss = loss(error, timedOut = false)
                        reportLoss(loss)
                        DirectResponse(null, textOf(incoming), Interruption.CONNECTION, loss.reason)
                    }
                },
            )
        }
    }

    /**
     * Runs a command with binary output (`screencap -p`). dadb's shell v2 protocol keeps stdout and stderr apart
     * and uses no terminal, so line endings are not translated. Not replayed.
     */
    override suspend fun readBinary(command: String): BinaryOutput = withContext(Dispatchers.IO) {
        lock.withLock {
            if (session == null) resume()
            val active = session ?: return@withLock BinaryOutput(null, ByteArray(0), reason = noSessionReason())
            val output = ByteArrayOutputStream()
            val errors = ByteArrayOutputStream()
            var code: Int? = null

            withWatchdog(active, COMMAND_TIMEOUT_MS) {
                active.openShell(command).use { stream ->
                    while (code == null) {
                        when (val packet = stream.read()) {
                            is AdbShellPacket.Exit -> code = packet.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                            is AdbShellPacket.StdError -> errors.write(packet.payload)
                            else -> output.write(packet.payload)
                        }
                    }
                }
            }.fold(
                onSuccess = { BinaryOutput(code, output.toByteArray(), textOf(errors)) },
                onFailure = { error ->
                    Log.w(TAG, "Binary read interrupted" + detail(command), error)
                    closeSession()
                    if (error is TimedOut) {
                        BinaryOutput(null, ByteArray(0), reason = timeoutReason())
                    } else {
                        val loss = loss(error, timedOut = false)
                        reportLoss(loss)
                        BinaryOutput(null, ByteArray(0), reason = loss.reason)
                    }
                },
            )
        }
    }

    private fun textOf(bytes: ByteArrayOutputStream): String = String(bytes.toByteArray(), Charsets.UTF_8).trimEnd()

    private sealed interface Opening {
        data class Succeeded(val session: Dadb) : Opening
        data class Failed(val error: Throwable, val problem: ConnectionProblem) : Opening
    }

    /** A command's outcome: an answer, or a session to reopen. */
    private sealed interface Outcome {
        data class Answered(val result: ShellResult) : Outcome
        data class Broken(val reason: String, val problem: ConnectionProblem) : Outcome
    }

    private suspend fun open(host: String, port: Int, timeoutMs: Long): Opening {
        val opened = try {
            // The socket read timeout is DELAI_CONNEXION_MS, time for the user to accept the prompt on the TV.
            // The shorter reconnect timeout is enforced by the watchdog.
            Dadb.create(host, port, keyStore.pair(), TCP_TIMEOUT_MS, CONNECTION_TIMEOUT_MS.toInt())
        } catch (error: Exception) {
            return Opening.Failed(error, diagnostic(error))
        }
        // A harmless round trip forces the handshake, and thus the authorization prompt, now.
        return withWatchdog(opened, timeoutMs) { opened.shell("echo tvslim") }.fold(
            onSuccess = { Opening.Succeeded(opened) },
            onFailure = { error ->
                runCatching { opened.close() }
                Opening.Failed(error, diagnostic(error))
            },
        )
    }

    private suspend fun attempt(command: String): Outcome {
        val active = session ?: return Outcome.Broken(noSessionReason(), ConnectionProblem.OTHER)

        // Without a real timeout, a TV that freezes or sleeps mid-command would hold the lock forever.
        return withWatchdog(active, COMMAND_TIMEOUT_MS) { active.shell(command) }.fold(
            onSuccess = { output ->
                Outcome.Answered(
                    ShellResult(
                        code = output.exitCode,
                        output = listOf(output.output, output.errorOutput)
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                            .trim(),
                    ),
                )
            },
            onFailure = { error ->
                val timedOut = error is TimedOut
                Log.w(TAG, (if (timedOut) "Timed out" else "Command interrupted") + detail(command), error)
                closeSession()
                loss(error, timedOut)
            },
        )
    }

    /**
     * Reopens the session to the same TV; the key is already authorized, so no prompt appears.
     *
     * After a failure, reconnects pause for [REST_AFTER_FAILURE_MS]. Otherwise disabling eighty packages on a TV
     * that was just turned off would attempt eighty reconnects.
     */
    private suspend fun resume(): Boolean {
        if (!retryAllowed) return false
        val (host, port) = target ?: return false
        val now = System.currentTimeMillis()
        if (now - lastRetryFailure < REST_AFTER_FAILURE_MS) return false

        Log.i(TAG, "Session dropped, reconnecting" + detail("$host:$port"))
        _connection.value = _connection.value.copy(state = ConnectionState.CONNECTION)
        return when (val outcome = open(host, port, RETRY_DELAY_MS)) {
            is Opening.Succeeded -> {
                session = outcome.session
                lastRetryFailure = 0L
                _connection.value = ConnectionUi(ConnectionState.CONNECTED, host, port)
                true
            }

            is Opening.Failed -> {
                Log.w(TAG, "Reconnect failed" + detail("$host:$port"), outcome.error)
                lastRetryFailure = now
                false
            }
        }
    }

    /**
     * Runs a blocking dadb call with a real timeout.
     *
     * `withTimeout` cancels the coroutine but not the blocked socket read, which would keep the lock. Closing
     * the session from another coroutine makes the read throw at once; that is reported as [TimedOut].
     */
    private suspend fun <T> withWatchdog(active: Dadb, timeoutMs: Long, call: () -> T): Result<T> = coroutineScope {
        val exceeded = AtomicBoolean(false)
        val watchdog = launch(Dispatchers.IO) {
            delay(timeoutMs)
            exceeded.set(true)
            runCatching { active.close() }
        }
        try {
            Result.success(call())
        } catch (error: Exception) {
            Result.failure(if (exceeded.get()) TimedOut(error) else error)
        } finally {
            watchdog.cancel()
        }
    }

    private class TimedOut(cause: Throwable) : IOException("délai dépassé", cause)

    /** A lost session: a localized reason, plus its cause for the connection screen. */
    private fun loss(error: Throwable, timedOut: Boolean): Outcome.Broken =
        if (timedOut) {
            Outcome.Broken(timeoutReason(), ConnectionProblem.TIMEOUT)
        } else {
            Outcome.Broken(
                error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                diagnostic(error),
            )
        }

    private fun reportLoss(outcome: Outcome.Broken) {
        _connection.value = _connection.value.copy(
            state = ConnectionState.ERROR,
            problem = outcome.problem,
            detail = outcome.reason,
        )
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
    }

    private fun diagnostic(error: Throwable): ConnectionProblem =
        diagnose(error, timedOut = error is TimedOut)

    // These reasons end up in the engine's results, next to the TV's own output.
    private fun noSessionReason(): String = context.getString(R.string.adb_no_session)

    private fun timeoutReason(): String = context.getString(R.string.adb_timeout)

    private companion object {
        const val TAG = "TVSlim/Adb"

        /** TCP connect: a TV on the local network answers within milliseconds. */
        const val TCP_TIMEOUT_MS = 5_000

        /** Long: the connection waits for someone to accept the prompt on the TV. */
        const val CONNECTION_TIMEOUT_MS = 45_000L

        /** Package commands take tens of ms; `dumpsys meminfo` can take seconds on a small box. Same as Windows. */
        const val COMMAND_TIMEOUT_MS = 30_000L

        /** Short: a reconnect needs no prompt, it either succeeds or the device is asleep. */
        const val RETRY_DELAY_MS = 12_000L

        const val REST_AFTER_FAILURE_MS = 20_000L

        /** Reads normally take a few hundred ms; anything slower is logged. */
        const val SLOW_THRESHOLD_MS = 2_000L

        /** Fixed part of the install timeout: Android verifies the package before answering. */
        const val INSTALL_TIMEOUT_MS = 120_000L

        /** Plus upload time: two seconds per megabyte, enough for poor Wi-Fi. */
        const val TIMEOUT_PER_MB_MS = 2_000L
        const val BYTES_PER_MB = 1_000_000L

        /** A minute without a byte sent means the link is dead. */
        const val IDLE_TIMEOUT_MS = 60_000L

        /** `rw-r--r--`, as `adb push` sets; shared storage ignores it anyway. */
        const val FILE_MODE = 0b110_100_100

        /** `-r` replaces an installed version and keeps its data; `-t` allows test builds. */
        val OPTIONS_INSTALLATION = arrayOf("-r", "-t")
    }
}

/**
 * Counts bytes sent, since dadb reports no progress. Reports every 1% (at least 64 KiB) rather than on every
 * 8 KiB block.
 */
private class CountingSource(
    source: Source,
    private val total: Long,
    private val onSent: (sent: Long, total: Long) -> Unit,
) : ForwardingSource(source) {

    private var sent = 0L
    private var lastReported = 0L
    private val step = maxOf(total / 100, 64L * 1024)

    override fun read(sink: Buffer, byteCount: Long): Long {
        val readResult = super.read(sink, byteCount)
        if (readResult > 0) sent += readResult
        val end = readResult < 0 || sent >= total
        if (sent > lastReported && (end || sent - lastReported >= step)) {
            lastReported = sent
            onSent(sent, total)
        }
        return readResult
    }
}
