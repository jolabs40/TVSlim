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

/**
 * La capture d'écran se lit par la sortie standard de `screencap -p` : ce test vérifie qu'on n'accepte qu'un vrai
 * PNG, et qu'on dit pourquoi quand ce n'en est pas un.
 */
class CaptureEcranTest {

    /** L'en-tête d'un PNG de 1920 × 1080 : signature, puis le bloc IHDR. */
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
    fun `un PNG complet donne la capture et ses dimensions`() = runTest {
        val tv = Televiseur(SortieBinaire(0, png1080p))

        val resultat = CaptureEcran(tv).capturer() as ResultatCapture.Reussie

        assertEquals("screencap -p", tv.commande)
        assertEquals(1920, resultat.largeur)
        assertEquals(1080, resultat.hauteur)
        assertTrue(resultat.png.contentEquals(png1080p))
    }

    @Test
    fun `un PNG dont les fins de ligne ont ete traduites est refuse`() = runTest {
        // Ce qu'un shell avec terminal fait d'un PNG : chaque \n précédé d'un \r.
        val traduit = png1080p.copyOf(6) + byteArrayOf(0x0D, 0x0D, 0x0A, 0x1A, 0x0D, 0x0A) + png1080p.copyOfRange(8, 29)

        val resultat = CaptureEcran(Televiseur(SortieBinaire(0, traduit))).capturer()

        assertEquals(CauseCapture.ILLISIBLE, (resultat as ResultatCapture.Echouee).cause)
    }

    @Test
    fun `une sortie texte dit ce que le televiseur a repondu`() = runTest {
        val tv = Televiseur(SortieBinaire(0, "/system/bin/sh: screencap: inaccessible or not found".toByteArray()))

        val resultat = CaptureEcran(tv).capturer() as ResultatCapture.Echouee

        assertEquals(CauseCapture.ILLISIBLE, resultat.cause)
        assertTrue(resultat.detail.contains("screencap"))
    }

    @Test
    fun `un code d'erreur et une connexion perdue se distinguent`() = runTest {
        val refusee = CaptureEcran(Televiseur(SortieBinaire(1, ByteArray(0), erreurs = "Permission denial\n"))).capturer()
        val coupee = CaptureEcran(Televiseur(SortieBinaire(null, ByteArray(0), motif = "délai dépassé"))).capturer()

        assertEquals(ResultatCapture.Echouee(CauseCapture.REFUSEE, "Permission denial"), refusee)
        assertEquals(ResultatCapture.Echouee(CauseCapture.CONNEXION, "délai dépassé"), coupee)
    }

    @Test
    fun `les dimensions ne se lisent que sur un PNG`() {
        assertEquals(1920 to 1080, CaptureEcran.dimensions(png1080p))
        assertNull(CaptureEcran.dimensions(png1080p.copyOf(20)))
        assertNull(CaptureEcran.dimensions(ByteArray(0)))
    }

    @Test
    fun `le nom de fichier porte l'appareil et l'instant, sans caractere interdit`() {
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
