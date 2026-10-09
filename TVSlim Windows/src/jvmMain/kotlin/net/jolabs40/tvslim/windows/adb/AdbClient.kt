package net.jolabs40.tvslim.windows.adb

import dadb.AdbShellPacket
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
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
import net.jolabs40.tvslim.shell.FileUploader
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.DirectExecutor
import net.jolabs40.tvslim.shell.ApkInstaller
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.shell.BinaryReader
import net.jolabs40.tvslim.shell.FileReceiver
import net.jolabs40.tvslim.shell.DirectResponse
import net.jolabs40.tvslim.shell.ShellResult
import net.jolabs40.tvslim.shell.BinaryOutput
import net.jolabs40.tvslim.windows.tools.AppLog
import net.jolabs40.tvslim.windows.tools.detail
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.sink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState { DISCONNECTED, CONNECTION, CONNECTED, ERROR }

/** Why a connection failed, so the UI can explain it in the user's language. */
enum class ConnectionProblem { REJECTED, TIMEOUT, UNAUTHORIZED, UNREACHABLE, OTHER }

data class ConnectionUi(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val host: String = "",
    val port: Int = DEFAULT_ADB_PORT,
    val problem: ConnectionProblem? = null,
    /** Original technical message, shown in small print under the explanation. */
    val detail: String = "",
)

const val DEFAULT_ADB_PORT = 5555

/**
 * ADB client from the PC to a TV in pure Kotlin (dadb): no `adb.exe`, no ADB server. Same library as the
 * Android companion.
 *
 * The first connection shows "Allow USB debugging?" on the TV. Once accepted, the public key is stored in
 * `/data/misc/adb/adb_keys` and the authorization survives reboots.
 *
 * Two dadb behaviours to work around:
 *  - dadb opens the socket lazily on the first command, so [connect] sends one right away and only reports
 *    connected once the TV has accepted;
 *  - `withTimeout` does not interrupt a blocked socket read, so timeouts are enforced by [withWatchdog],
 *    which closes the session from another thread.
 */
class AdbClient(
    private val keyStore: AdbKeyStore,
) : CommandExecutor, ApkInstaller, DirectExecutor, FileUploader, FileReceiver, BinaryReader {

    private val _connection = MutableStateFlow(ConnectionUi())
    val connection: StateFlow<ConnectionUi> = _connection.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var session: Dadb? = null

    /** Last TV the user connected to; every reconnection targets it. */
    @Volatile
    private var target: Pair<String, Int>? = null

    /** Time of the last failed reconnection, so it is not retried on every command. */
    private var lastRetryFailure = 0L

    /** False for a secondary session ([openSecond]), which never reopens once broken. */
    private var retryAllowed = true

    /**
     * Opens the connection and waits for the TV to accept it. [quiet] is for attempts the user did not ask for
     * (e.g. when the window regains focus): failure is usual there (TV off) and no error is shown.
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
                    AppLog.warn(TAG, "Connection failed" + detail("$host:$port"), outcome.error)
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
     * Opens a second session to the same TV for the long reads prefetched on connection (apps, `dumpsys meminfo`,
     * storage), keeping the main session free. The key is already authorized, so nothing shows on the TV.
     *
     * It never reopens by itself; a failed prefetch is redone on demand by the main session. The caller closes it
     * with [disconnect], which is also how an ongoing read is interrupted. Returns null if no TV is connected or
     * it does not answer.
     */
    suspend fun openSecond(): AdbClient? {
        val (host, port) = target ?: return null
        val second = AdbClient(keyStore).apply { retryAllowed = false }
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
        // Deliberately without the lock: closing the socket is what unblocks a pending command, which holds it.
        closeSession()
        // An explicit disconnect must not be undone by an automatic reconnection.
        target = null
        _connection.value = ConnectionUi()
    }

    /**
     * Runs a command, reopening the session once and replaying if it was broken (a TV going to sleep drops the
     * session silently).
     *
     * Replay is safe only because every command sent here is idempotent: reads, `pm disable-user`, `pm enable`,
     * `am force-stop`, `settings put`, `pm grant`, opening a store page. Non-idempotent commands must not use
     * this path.
     */
    override suspend fun execute(command: String): ShellResult = withContext(Dispatchers.IO) {
        lock.withLock {
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
        }
    }

    /**
     * Streams an APK to `cmd package install`, without copying it to the TV first.
     *
     * Not routed through [execute]: a multi-megabyte upload is never replayed silently. A session dropped before
     * the upload is reopened; a break during it is reported. The timeout grows with the file size and leaves time
     * for Android's verification and for a Play Protect prompt.
     */
    override suspend fun install(apk: File, onSent: (sent: Long, total: Long) -> Unit): ShellResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (session == null) resume()
                val active = session ?: return@withLock ShellResult.unavailable(REASON_NO_SESSION)
                val total = apk.length()
                val timeout = INSTALL_TIMEOUT_MS + total / BYTES_PER_MB * TIMEOUT_PER_MB_MS

                withWatchdog(active, timeout) {
                    CountingSource(apk.source(), total, onSent).use { source ->
                        active.install(source, total, *OPTIONS_INSTALLATION)
                    }
                }.fold(
                    onSuccess = { response ->
                        when (response) {
                            is InstallResult.Success -> ShellResult(code = 0, output = "Success")
                            is InstallResult.Failure -> ShellResult(code = 1, output = response.reason.trim())
                        }
                    },
                    onFailure = { error ->
                        AppLog.warn(TAG, "Installation interrupted" + detail(apk.name), error)
                        closeSession()
                        val loss = Outcome.Broken(
                            reason = if (error is TimedOut) {
                                REASON_TIMEOUT
                            } else {
                                error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                            },
                            problem = diagnostic(error),
                        )
                        reportLoss(loss)
                        ShellResult.unavailable(loss.reason)
                    },
                )
            }
        }

    /**
     * Writes a file to the TV, like `adb push`. Not replayed. The timeout applies to inactivity, not total
     * duration: the session is closed if nothing is sent for [IDLE_TIMEOUT_MS].
     *
     * Cancelling stops only this file; dadb closes its stream and the session stays open.
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
                val active = session ?: return@withLock ShellResult.unavailable(REASON_NO_SESSION)
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
                            ShellResult.unavailable(REASON_CANCELLED)
                        } else {
                            AppLog.warn(TAG, "Upload interrupted" + detail(path), error)
                            closeSession()
                            val loss = Outcome.Broken(
                                reason = if (error is ProlongedSilence) {
                                    REASON_TIMEOUT
                                } else {
                                    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                                },
                                problem = diagnostic(error),
                            )
                            reportLoss(loss)
                            ShellResult.unavailable(loss.reason)
                        }
                    },
                )
            }
        }
    }

    /**
     * Reads a file from the TV, like `adb pull`. Not replayed; same inactivity timeout, and cancelling stops only
     * this file.
     *
     * A local write failure is not a lost connection: the session stays open and the error is returned with code 1.
     */
    override suspend fun receive(
        path: String,
        destination: OutputStream,
        size: Long,
        cancelled: () -> Boolean,
        onReceived: (received: Long) -> Unit,
    ): ShellResult = withContext(Dispatchers.IO) {
        lock.withLock {
            if (session == null) resume()
            val active = session ?: return@withLock ShellResult.unavailable(REASON_NO_SESSION)
            val activity = AtomicLong(System.currentTimeMillis())

            underWatch(active, activity, IDLE_TIMEOUT_MS) {
                // The sink does not close the stream; the local writer closes and commits it.
                val sink = ReceiveSink(destination.sink(), size, cancelled, activity, onReceived)
                active.pull(sink, path).also { sink.flush() }
            }.fold(
                onSuccess = { response ->
                    when (response) {
                        is SyncResult.Success -> ShellResult(code = 0, output = "")
                        is SyncResult.Failure -> ShellResult(code = 1, output = response.reason.trim())
                    }
                },
                onFailure = { error ->
                    when (error) {
                        is ReceiveCancelled -> ShellResult.unavailable(REASON_COPY_CANCELLED)
                        is LocalWriteFailed -> ShellResult(code = 1, output = error.message.orEmpty())
                        else -> {
                            AppLog.warn(TAG, "Copy interrupted" + detail(path), error)
                            closeSession()
                            val loss = Outcome.Broken(
                                reason = if (error is ProlongedSilence) {
                                    REASON_TIMEOUT
                                } else {
                                    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                                },
                                problem = diagnostic(error),
                            )
                            reportLoss(loss)
                            ShellResult.unavailable(loss.reason)
                        }
                    }
                },
            )
        }
    }

    /**
     * Runs a user-typed command exactly once: unlike [execute], it is never replayed since it may not be
     * idempotent. Output is read as it arrives, so a command that never ends (`logcat` without `-d`, `top`) still
     * returns what it printed when the timeout cuts it.
     *
     * The timeout closes the session (the only way to interrupt the read); the next command reopens it without
     * reporting an error.
     */
    override suspend fun executeOnce(command: String): DirectResponse = withContext(Dispatchers.IO) {
        lock.withLock {
            if (session == null) resume()
            val active = session
                ?: return@withLock DirectResponse(null, "", Interruption.CONNECTION, REASON_NO_SESSION)
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
                    AppLog.warn(TAG, "Free command interrupted" + detail(command), error)
                    closeSession()
                    if (error is TimedOut) {
                        DirectResponse(null, textOf(incoming), Interruption.TIMEOUT, "délai dépassé")
                    } else {
                        val loss = Outcome.Broken(
                            reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                            problem = diagnostic(error),
                        )
                        reportLoss(loss)
                        DirectResponse(null, textOf(incoming), Interruption.CONNECTION, loss.reason)
                    }
                },
            )
        }
    }

    /**
     * Runs a command with binary output (`screencap -p`). dadb's shell v2 protocol keeps stdout and stderr apart
     * with no terminal translating line endings. Not replayed.
     */
    override suspend fun readBinary(command: String): BinaryOutput = withContext(Dispatchers.IO) {
        lock.withLock {
            if (session == null) resume()
            val active = session ?: return@withLock BinaryOutput(null, ByteArray(0), reason = REASON_NO_SESSION)
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
                    AppLog.warn(TAG, "Binary read interrupted" + detail(command), error)
                    closeSession()
                    if (error is TimedOut) {
                        BinaryOutput(null, ByteArray(0), reason = REASON_TIMEOUT)
                    } else {
                        val loss = Outcome.Broken(
                            reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                            problem = diagnostic(error),
                        )
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

    private sealed interface Outcome {
        data class Answered(val result: ShellResult) : Outcome
        data class Broken(val reason: String, val problem: ConnectionProblem) : Outcome
    }

    private suspend fun open(host: String, port: Int, timeoutMs: Long): Opening {
        val opened = try {
            // Socket read timeout is DELAI_CONNEXION_MS, enough to accept the prompt on the TV. The shorter
            // reconnection timeout is enforced by sousSurveillance.
            Dadb.create(host, port, keyStore.pair(), TCP_TIMEOUT_MS, CONNECTION_TIMEOUT_MS.toInt())
        } catch (error: Exception) {
            return Opening.Failed(error, diagnostic(error))
        }
        // A harmless round trip forces the handshake, and so the authorization prompt, now.
        return withWatchdog(opened, timeoutMs) { opened.shell("echo tvslim") }.fold(
            onSuccess = { Opening.Succeeded(opened) },
            onFailure = { error ->
                runCatching { opened.close() }
                Opening.Failed(error, diagnostic(error))
            },
        )
    }

    private suspend fun attempt(command: String): Outcome {
        val active = session ?: return Outcome.Broken(REASON_NO_SESSION, ConnectionProblem.OTHER)

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
                AppLog.warn(
                    TAG,
                    (if (timedOut) "Timed out" else "Command interrupted") + detail(command),
                    error,
                )
                closeSession()
                if (timedOut) {
                    Outcome.Broken(REASON_TIMEOUT, ConnectionProblem.TIMEOUT)
                } else {
                    Outcome.Broken(
                        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                        diagnostic(error),
                    )
                }
            },
        )
    }

    /**
     * Reopens the session to the same TV; the key is already authorized, so no prompt appears.
     *
     * After a failure, reconnection pauses for [REST_AFTER_FAILURE_MS], otherwise disabling 80 packages on a TV
     * that was just switched off would attempt 80 reconnections.
     */
    private suspend fun resume(): Boolean {
        if (!retryAllowed) return false
        val (host, port) = target ?: return false
        val now = System.currentTimeMillis()
        if (now - lastRetryFailure < REST_AFTER_FAILURE_MS) return false

        AppLog.info(TAG, "Session lost, reconnecting" + detail("$host:$port"))
        _connection.value = _connection.value.copy(state = ConnectionState.CONNECTION)
        return when (val outcome = open(host, port, RETRY_DELAY_MS)) {
            is Opening.Succeeded -> {
                session = outcome.session
                lastRetryFailure = 0L
                _connection.value = ConnectionUi(ConnectionState.CONNECTED, host, port)
                true
            }

            is Opening.Failed -> {
                lastRetryFailure = now
                false
            }
        }
    }

    /**
     * Runs a blocking dadb call with a hard timeout.
     *
     * `withTimeout` cancels the coroutine but not the blocked socket read, which would keep the lock and freeze
     * the app. Closing the session from another thread makes the read throw at once; that is reported as
     * [TimedOut].
     */
    private suspend fun <T> withWatchdog(
        active: Dadb,
        timeoutMs: Long,
        call: () -> T,
    ): Result<T> = coroutineScope {
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

    private companion object {
        const val TAG = "Adb"

        /** A TV that is on, on the local network, accepts TCP within milliseconds. */
        const val TCP_TIMEOUT_MS = 5_000

        /** Long because the user must accept the prompt on the TV. */
        const val CONNECTION_TIMEOUT_MS = 45_000L

        /** Package commands answer in tens of ms; `dumpsys meminfo` can take seconds on a small box. */
        const val COMMAND_TIMEOUT_MS = 30_000L

        /** Short: a reconnection needs no prompt, it either succeeds or the device is asleep. */
        const val RETRY_DELAY_MS = 12_000L

        const val REST_AFTER_FAILURE_MS = 20_000L

        /** Fixed part of the install timeout: Android verifies the app before answering. */
        const val INSTALL_TIMEOUT_MS = 120_000L

        /** Plus 2 s per MB of upload, enough for poor Wi-Fi. */
        const val TIMEOUT_PER_MB_MS = 2_000L
        const val BYTES_PER_MB = 1_000_000L

        /** One minute without a byte moving means the link is dead. */
        const val IDLE_TIMEOUT_MS = 60_000L

        /** `rw-r--r--`, as `adb push` sets; shared storage ignores it anyway. */
        const val FILE_MODE = 0b110_100_100

        /** `-r` replaces an installed version keeping its data; `-t` allows test builds. */
        val OPTIONS_INSTALLATION = arrayOf("-r", "-t")

        // These reasons end up in engine results and in the journal, like the shared core's, which are French.
        const val REASON_NO_SESSION = "Aucun téléviseur connecté."
        const val REASON_CANCELLED = "Envoi annulé."
        const val REASON_COPY_CANCELLED = "Copie annulée."
        const val REASON_TIMEOUT =
            "Le téléviseur n'a pas répondu à temps. Vérifiez qu'il est allumé et réessayez."
    }
}

/**
 * Counts uploaded bytes, since dadb reports no progress. Reports every 1% (at least 64 KiB), not every 8 KiB
 * block.
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
