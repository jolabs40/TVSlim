package net.jolabs40.tvslim.remote.adb

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
 * Exécute un envoi de dadb sous un délai de **silence**, et non de durée : un film de plusieurs gigaoctets prend
 * son temps, et c'est normal ; une liaison qui ne fait plus rien passer pendant [silenceMaxMs], non.
 *
 * Même principe que `sousSurveillance` : seule la fermeture de la session débloque une écriture de socket
 * suspendue, et elle se fait depuis un autre fil. Le même fichier vit dans la version Windows.
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

/** Levée par la source quand la personne arrête l'envoi : dadb l'interrompt, et ferme son flux. */
internal class EnvoiAnnule : IOException("envoi annulé")

/**
 * La source d'un fichier qui part : elle compte les octets — dadb ne dit rien pendant un envoi —, note que la
 * liaison vit, et s'arrête net quand [annule] devient vrai.
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

    /** Un signe par centième, et pas moins de 64 Ko : l'écran n'a que faire de dix mille mises à jour. */
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
