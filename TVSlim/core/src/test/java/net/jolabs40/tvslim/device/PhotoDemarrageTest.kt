package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le gardien compare deux allumages. Il ne doit parler qu'après une mise à jour système, et seulement de
 * ce qu'elle a défait.
 */
class PhotoDemarrageTest {

    private val usines = setOf(SETUPWRAITH, LAUNCHERX)

    /** La TCL de l'utilisateur la veille : publicité et Google TV coupés, Startlight en accueil. */
    private val veille = PhotoDemarrage(
        empreinte = "TCL/G08_4K_GB/14:20251205",
        desactives = setOf("com.tcl.pub", SETUPWRAITH, LAUNCHERX),
        accueil = STARTLIGHT,
    )

    /** Après la mise à jour : tout est revenu, Google TV a repris l'accueil. */
    private val apresMiseAJour = PhotoDemarrage(
        empreinte = "TCL/G08_4K_GB/14:20260312",
        desactives = emptySet(),
        accueil = LAUNCHERX,
    )
    private val tousActifs = setOf("com.tcl.pub", "com.tcl.demo", SETUPWRAITH, LAUNCHERX)

    @Test
    fun `le premier allumage ne fait que poser la reference`() {
        assertNull(apresMiseAJour.deriveDepuis(null, tousActifs, usines))
    }

    @Test
    fun `sans mise a jour systeme rien n'a derive`() {
        // Le même firmware : un paquet rallumé l'a été depuis le téléphone, c'est un choix.
        val memeFirmware = apresMiseAJour.copy(empreinte = veille.empreinte)

        assertNull(memeFirmware.deriveDepuis(veille, tousActifs, usines))
    }

    @Test
    fun `une mise a jour qui rallume tout et rend l'accueil a Google TV`() {
        val derive = apresMiseAJour.deriveDepuis(veille, tousActifs, usines)

        assertEquals(listOf("com.tcl.pub", LAUNCHERX, SETUPWRAITH).sorted(), derive?.rallumes)
        assertEquals(STARTLIGHT, derive?.accueilPerdu)
    }

    @Test
    fun `une mise a jour qui ne defait rien ne dit rien`() {
        val sage = veille.copy(empreinte = apresMiseAJour.empreinte)
        val actifs = tousActifs - veille.desactives

        assertNull(sage.deriveDepuis(veille, actifs, usines))
    }

    @Test
    fun `un paquet retire par la mise a jour n'est pas rallume`() {
        val actifs = tousActifs - "com.tcl.pub"
        val derive = apresMiseAJour.deriveDepuis(veille, actifs, usines)

        assertEquals(listOf(LAUNCHERX, SETUPWRAITH).sorted(), derive?.rallumes)
    }

    @Test
    fun `un accueil d'usine avant la mise a jour n'est pas perdu`() {
        val surGoogleTv = veille.copy(accueil = LAUNCHERX)
        val derive = apresMiseAJour.deriveDepuis(surGoogleTv, tousActifs, usines)

        assertNull(derive?.accueilPerdu)
    }

    @Test
    fun `le selecteur d'Android compte comme un accueil retombe`() {
        val selecteur = apresMiseAJour.copy(accueil = "android")

        assertEquals(STARTLIGHT, selecteur.deriveDepuis(veille, tousActifs, usines)?.accueilPerdu)
    }

    @Test
    fun `ce qui a ete repris depuis ne reste pas`() {
        val derive = DeriveDemarrage(rallumes = listOf("com.tcl.pub", LAUNCHERX), accueilPerdu = STARTLIGHT)
        // Le téléphone a recoupé Google TV et rendu l'accueil à Startlight ; la publicité reste allumée.
        val restant = derive.restant(actifs = setOf("com.tcl.pub"), accueil = STARTLIGHT, accueilsUsine = usines)

        assertEquals(listOf("com.tcl.pub"), restant.rallumes)
        assertNull(restant.accueilPerdu)
        assertTrue(derive.restant(emptySet(), STARTLIGHT, usines).vide)
    }

    private companion object {
        const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
        const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
        const val STARTLIGHT = "net.jolabs40.startlight"
    }
}
