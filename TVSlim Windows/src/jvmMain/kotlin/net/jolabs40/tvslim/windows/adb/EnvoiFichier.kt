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
 * Like `sousSurveillance`, only closing the session from another thread unblocks a stuck socket write.
 */
internal suspend fun <T> sousVeille(
    active: Dadb,
    activite: AtomicLong,
    silenceMaxMs: Long,
    appel: () -> T,
): Result<T> = coroutineScope {
    val depasse = AtomicBoolean(false)
    val chien = launch(Dispatchers.IO) {
        while (System.currentTimeMillis() - activite.get() <= silenceMaxMs) delay(PAS_VEILLE_MS)
        depasse.set(true)
        runCatching { active.close() }
    }
    try {
        Result.success(appel())
    } catch (erreur: Exception) {
        Result.failure(if (depasse.get()) SilenceProlonge(erreur) else erreur)
    } finally {
        chien.cancel()
    }
}

internal class SilenceProlonge(cause: Throwable) : IOException("plus rien ne passe", cause)

/** Thrown by the source when the user cancels; dadb aborts the transfer and closes its stream. */
internal class EnvoiAnnule : IOException("envoi annulé")

/**
 * Upload source that counts bytes (dadb reports no progress), records activity for [sousVeille], and stops as
 * soon as [annule] returns true.
 */
internal class SourceEnvoi(
    source: Source,
    private val taille: Long,
    private val annule: () -> Boolean,
    private val activite: AtomicLong,
    private val surEnvoi: (envoye: Long) -> Unit,
) : ForwardingSource(source) {

    private var envoye = 0L
    private var signale = 0L

    /** Progress step: 1% of the size, at least 64 KiB. */
    private val pas = maxOf(taille / 100, 64L * 1024)

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (annule()) throw EnvoiAnnule()
        val lus = super.read(sink, byteCount)
        activite.set(System.currentTimeMillis())
        if (lus > 0) envoye += lus
        if (envoye > signale && (lus < 0 || envoye - signale >= pas)) {
            signale = envoye
            surEnvoi(envoye)
        }
        return lus
    }
}

private const val PAS_VEILLE_MS = 1_000L
