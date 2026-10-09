package net.jolabs40.tvslim.windows.adb

import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs a dadb transfer with an inactivity timeout rather than a duration limit: a multi-GB file may take long,
 * but nothing moving for [silenceMaxMs] means the link is dead.
 *
 * Like `withWatchdog`, only closing the session from another thread unblocks a stuck socket write.
 */
internal suspend fun <T> underWatch(
    active: Dadb,
    activity: AtomicLong,
    silenceMaxMs: Long,
    call: () -> T,
): Result<T> = coroutineScope {
    val exceeded = AtomicBoolean(false)
    val watchdog = launch(Dispatchers.IO) {
        while (System.currentTimeMillis() - activity.get() <= silenceMaxMs) delay(WATCH_STEP_MS)
        exceeded.set(true)
        runCatching { active.close() }
    }
    try {
        Result.success(call())
    } catch (error: Exception) {
        Result.failure(if (exceeded.get()) ProlongedSilence(error) else error)
    } finally {
        watchdog.cancel()
    }
}

internal class ProlongedSilence(cause: Throwable) : IOException("no data moving", cause)

/** Thrown by the source when the user cancels; dadb aborts the transfer and closes its stream. */
internal class UploadCancelled : IOException("upload cancelled")

/**
 * Upload source that counts bytes (dadb reports no progress), records activity for [underWatch], and stops as
 * soon as [cancelled] returns true.
 */
internal class UploadSource(
    source: Source,
    private val size: Long,
    private val cancelled: () -> Boolean,
    private val activity: AtomicLong,
    private val onSent: (sent: Long) -> Unit,
) : ForwardingSource(source) {

    private var sent = 0L
    private var lastReported = 0L

    /** Progress step: 1% of the size, at least 64 KiB. */
    private val step = maxOf(size / 100, 64L * 1024)

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (cancelled()) throw UploadCancelled()
        val readResult = super.read(sink, byteCount)
        activity.set(System.currentTimeMillis())
        if (readResult > 0) sent += readResult
        if (sent > lastReported && (readResult < 0 || sent - lastReported >= step)) {
            lastReported = sent
            onSent(sent)
        }
        return readResult
    }
}

private const val WATCH_STEP_MS = 1_000L
