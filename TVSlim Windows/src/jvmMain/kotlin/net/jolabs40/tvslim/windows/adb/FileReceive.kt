package net.jolabs40.tvslim.windows.adb

import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Thrown by the sink when the user cancels; dadb aborts the transfer and closes its stream. */
internal class ReceiveCancelled : IOException("copie annulée")

/** The local disk refused the write (full, removed, read-only); the connection itself is fine. */
internal class LocalWriteFailed(cause: IOException) : IOException(cause.message ?: cause.javaClass.simpleName, cause)

/**
 * Download sink, counterpart of [UploadSource]: counts bytes, records activity, stops when [cancelled] returns true,
 * and tells disk errors apart from connection errors.
 */
internal class ReceiveSink(
    sink: Sink,
    private val size: Long,
    private val cancelled: () -> Boolean,
    private val activity: AtomicLong,
    private val onReceived: (received: Long) -> Unit,
) : ForwardingSink(sink) {

    private var received = 0L
    private var lastReported = 0L

    /** Progress step: 1% of the size, at least 64 KiB. */
    private val step = maxOf(size / 100, 64L * 1024)

    override fun write(source: Buffer, byteCount: Long) {
        if (cancelled()) throw ReceiveCancelled()
        activity.set(System.currentTimeMillis())
        onDisk { super.write(source, byteCount) }
        received += byteCount
        if (received - lastReported >= step || (size > 0 && received >= size)) {
            lastReported = received
            onReceived(received)
        }
    }

    override fun flush() = onDisk { super.flush() }

    private inline fun onDisk(writing: () -> Unit) {
        try {
            writing()
        } catch (error: IOException) {
            throw LocalWriteFailed(error)
        }
    }
}
