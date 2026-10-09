package net.jolabs40.tvslim.windows.adb

import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Thrown by the sink when the user cancels; dadb aborts the transfer and closes its stream. */
internal class ReceptionAnnulee : IOException("copie annulée")

/** The local disk refused the write (full, removed, read-only); the connection itself is fine. */
internal class EcritureLocaleEchouee(cause: IOException) : IOException(cause.message ?: cause.javaClass.simpleName, cause)

/**
 * Download sink, counterpart of [SourceEnvoi]: counts bytes, records activity, stops when [annule] returns true,
 * and tells disk errors apart from connection errors.
 */
internal class PuitsReception(
    puits: Sink,
    private val taille: Long,
    private val annule: () -> Boolean,
    private val activite: AtomicLong,
    private val surRecu: (recu: Long) -> Unit,
) : ForwardingSink(puits) {

    private var recu = 0L
    private var signale = 0L

    /** Progress step: 1% of the size, at least 64 KiB. */
    private val pas = maxOf(taille / 100, 64L * 1024)

    override fun write(source: Buffer, byteCount: Long) {
        if (annule()) throw ReceptionAnnulee()
        activite.set(System.currentTimeMillis())
        surDisque { super.write(source, byteCount) }
        recu += byteCount
        if (recu - signale >= pas || (taille > 0 && recu >= taille)) {
            signale = recu
            surRecu(recu)
        }
    }

    override fun flush() = surDisque { super.flush() }

    private inline fun surDisque(ecriture: () -> Unit) {
        try {
            ecriture()
        } catch (erreur: IOException) {
            throw EcritureLocaleEchouee(erreur)
        }
    }
}
