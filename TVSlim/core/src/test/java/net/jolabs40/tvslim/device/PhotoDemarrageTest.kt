package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guardian compares two boots and only reports after a system update, and only what it undid. */
class PhotoDemarrageTest {

    private val usines = setOf(SETUPWRAITH, LAUNCHERX)

    /** The TCL the day before: ads and Google TV disabled, Startlight as home. */
    private val veille = PhotoDemarrage(
        empreinte = "TCL/G08_4K_GB/14:20251205",
        desactives = setOf("com.tcl.pub", SETUPWRAITH, LAUNCHERX),
        accueil = STARTLIGHT,
    )

    /** After the update: everything is back and Google TV is home again. */
    private val apresMiseAJour = PhotoDemarrage(
        empreinte = "TCL/G08_4K_GB/14:20260312",
        desactives = emptySet(),
        accueil = LAUNCHERX,
    )
    private val tousActifs = setOf("com.tcl.pub", "com.tcl.demo", SETUPWRAITH, LAUNCHERX)

    @Test
    fun `the first boot only sets the baseline`() {
        assertNull(apresMiseAJour.deriveDepuis(null, tousActifs, usines))
    }

    @Test
    fun `without a system update nothing has drifted`() {
        // Same firmware: a re-enabled package was turned on from the phone, on purpose.
        val memeFirmware = apresMiseAJour.copy(empreinte = veille.empreinte)

        assertNull(memeFirmware.deriveDepuis(veille, tousActifs, usines))
    }

    @Test
    fun `an update that re-enables everything and gives home back to Google TV`() {
        val derive = apresMiseAJour.deriveDepuis(veille, tousActifs, usines)

        assertEquals(listOf("com.tcl.pub", LAUNCHERX, SETUPWRAITH).sorted(), derive?.rallumes)
        assertEquals(STARTLIGHT, derive?.accueilPerdu)
    }

    @Test
    fun `an update that undoes nothing reports nothing`() {
        val sage = veille.copy(empreinte = apresMiseAJour.empreinte)
        val actifs = tousActifs - veille.desactives

        assertNull(sage.deriveDepuis(veille, actifs, usines))
    }

    @Test
    fun `a package removed by the update is not reported as re-enabled`() {
        val actifs = tousActifs - "com.tcl.pub"
        val derive = apresMiseAJour.deriveDepuis(veille, actifs, usines)

        assertEquals(listOf(LAUNCHERX, SETUPWRAITH).sorted(), derive?.rallumes)
    }

    @Test
    fun `a factory home before the update is not lost`() {
        val surGoogleTv = veille.copy(accueil = LAUNCHERX)
        val derive = apresMiseAJour.deriveDepuis(surGoogleTv, tousActifs, usines)

        assertNull(derive?.accueilPerdu)
    }

    @Test
    fun `the Android chooser counts as a home that fell back`() {
        val selecteur = apresMiseAJour.copy(accueil = "android")

        assertEquals(STARTLIGHT, selecteur.deriveDepuis(veille, tousActifs, usines)?.accueilPerdu)
    }

    @Test
    fun `what has been fixed since does not remain`() {
        val derive = DeriveDemarrage(rallumes = listOf("com.tcl.pub", LAUNCHERX), accueilPerdu = STARTLIGHT)
        // The phone disabled Google TV again and restored Startlight; the ads package is still on.
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
