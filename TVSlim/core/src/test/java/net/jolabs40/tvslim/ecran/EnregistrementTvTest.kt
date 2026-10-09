package net.jolabs40.tvslim.ecran

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.RecepteurFichiers
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * On-TV recording against a fake TV: commands sent, their order, and the outcome. The real `screenrecord` is
 * covered on the TCL by `EcranMaterielTest` (Windows).
 */
class EnregistrementTvTest {

    private val aide14 = """
        Usage: screenrecord [options] <filename>
        --time-limit TIME
            Set the maximum recording time, in seconds.  Default is 180. Set to 0
            to remove the time limit.
    """.trimIndent()

    private val aide11 = """
        Usage: screenrecord [options] <filename>
        --time-limit TIME
            Set the maximum recording time, in seconds.  Default / maximum is 180.
    """.trimIndent()

    private class Televiseur(
        val aide: ResultatShell,
        /** False: the recorder dies right after launch. */
        val demarre: Boolean = true,
        var taille: Long = 4_000_000,
    ) : ExecuteurCommande, RecepteurFichiers {
        val commandes = mutableListOf<String>()
        var vivant = false
        var lu = ""

        override suspend fun executer(commande: String): ResultatShell {
            commandes += commande
            return when {
                commande.startsWith("screenrecord --help") -> aide
                commande.contains("setsid") -> {
                    vivant = demarre
                    ResultatShell(0, "")
                }
                commande.contains("kill -INT") && commande.contains("stat -c") -> {
                    vivant = false
                    ResultatShell(0, "$taille\n")
                }
                commande.contains("kill -0") -> ResultatShell(if (vivant) 0 else 1, "")
                commande.startsWith("cat ") -> ResultatShell(0, "ERROR: unable to configure video encoder\n")
                else -> ResultatShell(0, "")
            }
        }

        override suspend fun recevoir(
            chemin: String,
            destination: OutputStream,
            taille: Long,
            annule: () -> Boolean,
            surRecu: (recu: Long) -> Unit,
        ): ResultatShell {
            lu = chemin
            destination.write(byteArrayOf(1, 2, 3))
            surRecu(3)
            return ResultatShell(0, "")
        }
    }

    @Test
    fun `on Android 14 the recorder starts detached and without a time limit`() = runTest {
        val tv = Televiseur(ResultatShell(0, aide14))

        val demarrage = EnregistrementTv(tv, tv).demarrer()

        assertEquals(Demarrage.Lance(limiteS = null), demarrage)
        val lancement = tv.commandes.single { it.contains("setsid") }
        assertTrue(lancement, lancement.contains("--time-limit 0"))
        assertTrue(lancement, lancement.contains(EnregistrementTv.VIDEO))
        // A replayed command must not start a second recorder on the same file.
        assertTrue(lancement, lancement.contains("echo deja"))
        // Leftovers from a previous session are stopped and deleted first.
        assertTrue(tv.commandes.indexOfFirst { it.startsWith("p=") && it.contains("rm -f") } < tv.commandes.indexOf(lancement))
    }

    @Test
    fun `before Android 14 recording is capped at three minutes`() = runTest {
        val tv = Televiseur(ResultatShell(0, aide11))

        assertEquals(Demarrage.Lance(limiteS = 180), EnregistrementTv(tv, tv).demarrer())
        assertTrue(tv.commandes.single { it.contains("setsid") }.contains("--time-limit 180"))
    }

    @Test
    fun `without screenrecord nothing is started`() = runTest {
        val tv = Televiseur(ResultatShell(127, "/system/bin/sh: screenrecord: inaccessible or not found"))

        val demarrage = EnregistrementTv(tv, tv).demarrer()

        assertEquals(CauseEnregistrement.INDISPONIBLE, (demarrage as Demarrage.Refuse).cause)
        assertFalse(tv.commandes.any { it.contains("setsid") })
    }

    @Test
    fun `a recorder that dies right away reports why`() = runTest {
        val tv = Televiseur(ResultatShell(0, aide14), demarre = false)

        val demarrage = EnregistrementTv(tv, tv).demarrer()

        assertEquals(
            Demarrage.Refuse(CauseEnregistrement.ECHEC, "ERROR: unable to configure video encoder"),
            demarrage,
        )
    }

    @Test
    fun `a lost connection is reported as such`() = runTest {
        val tv = Televiseur(ResultatShell.indisponible("Aucun téléviseur connecté."))

        assertEquals(
            Demarrage.Refuse(CauseEnregistrement.CONNEXION, "Aucun téléviseur connecté."),
            EnregistrementTv(tv, tv).demarrer(),
        )
    }

    @Test
    fun `stopping sends SIGINT, then the video is copied and deleted`() = runTest {
        val tv = Televiseur(ResultatShell(0, aide14))
        val enregistrement = EnregistrementTv(tv, tv)
        enregistrement.demarrer()
        assertEquals(true, enregistrement.vivant())

        assertEquals(Arret.Termine(4_000_000), enregistrement.arreter())
        assertEquals(false, enregistrement.vivant())

        val recue = ByteArrayOutputStream()
        assertTrue(enregistrement.rapatrier(recue, 4_000_000).reussi)
        assertEquals(EnregistrementTv.VIDEO, tv.lu)
        assertEquals(3, recue.size())

        enregistrement.nettoyer()
        assertTrue(tv.commandes.last().startsWith("rm -f ${EnregistrementTv.VIDEO}"))
    }

    @Test
    fun `a recording without a file is not copied`() = runTest {
        val tv = Televiseur(ResultatShell(0, aide14), taille = 0)
        val enregistrement = EnregistrementTv(tv, tv)
        enregistrement.demarrer()

        assertEquals(CauseEnregistrement.VIDE, (enregistrement.arreter() as Arret.Refuse).cause)
    }
}
