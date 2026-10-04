package net.jolabs40.tvslim.windows.adb

import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Levée par le puits quand la personne arrête la copie : dadb l'interrompt, et ferme son flux. */
internal class ReceptionAnnulee : IOException("copie annulée")

/** Le disque a refusé d'écrire — plein, retiré, protégé : ce n'est pas la connexion qui a lâché. */
internal class EcritureLocaleEchouee(cause: IOException) : IOException(cause.message ?: cause.javaClass.simpleName, cause)

/**
 * Le puits d'un fichier qui arrive du téléviseur, pendant de [SourceEnvoi] : il compte les octets — dadb ne dit
 * rien pendant une copie —, note que la liaison vit, s'arrête net quand [annule] devient vrai, et distingue une
 * erreur du disque d'une rupture de la connexion.
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

    /** Un signe par centième, et pas moins de 64 Ko : l'écran n'a que faire de dix mille mises à jour. */
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
