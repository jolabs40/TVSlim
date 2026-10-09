package net.jolabs40.tvslim.ecran

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.shell.LecteurBinaire
import net.jolabs40.tvslim.shell.SortieBinaire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** The screenshot is read from the stdout of `screencap -p`; only a real PNG is accepted. */
class CaptureEcranTest {

    /** Header of a 1920x1080 PNG: signature, then the IHDR chunk. */
    private val png1080p = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x07, 0x80.toByte(), 0x00, 0x00, 0x04, 0x38,
        0x08, 0x06, 0x00, 0x00, 0x00,
    )

    private class Televiseur(val reponse: SortieBinaire) : LecteurBinaire {
        var commande = ""

        override suspend fun lireBinaire(commande: String): SortieBinaire {
            this.commande = commande
            return reponse
        }
    }

    @Test
    fun `a complete PNG gives the screenshot and its dimensions`() = runTest {
        val tv = Televiseur(SortieBinaire(0, png1080p))

        val resultat = CaptureEcran(tv).capturer() as ResultatCapture.Reussie

        assertEquals("screencap -p", tv.commande)
        assertEquals(1920, resultat.largeur)
        assertEquals(1080, resultat.hauteur)
        assertTrue(resultat.png.contentEquals(png1080p))
    }

    @Test
    fun `a PNG with translated line endings is rejected`() = runTest {
        // What a shell with a terminal does to a PNG: every \n gets a \r before it.
        val traduit = png1080p.copyOf(6) + byteArrayOf(0x0D, 0x0D, 0x0A, 0x1A, 0x0D, 0x0A) + png1080p.copyOfRange(8, 29)

        val resultat = CaptureEcran(Televiseur(SortieBinaire(0, traduit))).capturer()

        assertEquals(CauseCapture.ILLISIBLE, (resultat as ResultatCapture.Echouee).cause)
    }

    @Test
    fun `a text output reports what the tv answered`() = runTest {
        val tv = Televiseur(SortieBinaire(0, "/system/bin/sh: screencap: inaccessible or not found".toByteArray()))

        val resultat = CaptureEcran(tv).capturer() as ResultatCapture.Echouee

        assertEquals(CauseCapture.ILLISIBLE, resultat.cause)
        assertTrue(resultat.detail.contains("screencap"))
    }

    @Test
    fun `an error code and a lost connection are told apart`() = runTest {
        val refusee = CaptureEcran(Televiseur(SortieBinaire(1, ByteArray(0), erreurs = "Permission denial\n"))).capturer()
        val coupee = CaptureEcran(Televiseur(SortieBinaire(null, ByteArray(0), motif = "délai dépassé"))).capturer()

        assertEquals(ResultatCapture.Echouee(CauseCapture.REFUSEE, "Permission denial"), refusee)
        assertEquals(ResultatCapture.Echouee(CauseCapture.CONNEXION, "délai dépassé"), coupee)
    }

    @Test
    fun `dimensions are only read from a PNG`() {
        assertEquals(1920 to 1080, CaptureEcran.dimensions(png1080p))
        assertNull(CaptureEcran.dimensions(png1080p.copyOf(20)))
        assertNull(CaptureEcran.dimensions(ByteArray(0)))
    }

    @Test
    fun `the file name holds the device and the time, without forbidden characters`() {
        val instant = LocalDateTime.of(2026, 10, 4, 19, 15, 30)
        val tcl = InfosAppareil(marque = "TCL", modele = "Smart TV Pro")
        val bizarre = InfosAppareil(marque = "Philips", modele = "55PUS8807/12 : \"test\"")

        assertEquals("TVSlim-TCL-Smart-TV-Pro-2026-10-04_19-15-30.png", CaptureEcran.nomFichier(tcl, instant, "png"))
        assertEquals(
            "TVSlim-Philips-55PUS8807-12-test-2026-10-04_19-15-30.mp4",
            CaptureEcran.nomFichier(bizarre, instant, "mp4"),
        )
        assertEquals("TVSlim-televiseur-2026-10-04_19-15-30.png", CaptureEcran.nomFichier(InfosAppareil(), instant, "png"))
    }
}
