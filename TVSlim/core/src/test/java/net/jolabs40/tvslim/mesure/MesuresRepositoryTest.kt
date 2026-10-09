package net.jolabs40.tvslim.mesure

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MesuresRepositoryTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private fun fichier() = File(dossier.newFolder(), "mesures.json")

    private fun mesure(
        horodatage: Long,
        actifs: Int = 90,
        desactives: Int = 0,
        libre: Long = 500,
    ) = Mesure(
        horodatage = horodatage,
        paquetsActifs = actifs,
        paquetsDesactives = desactives,
        memoireTotaleMo = 2450,
        memoireLibreMo = libre,
    )

    @Test
    fun `the first measurement becomes the baseline and never moves`() = runTest {
        val depot = MesuresRepository(fichier())
        depot.charger()

        depot.enregistrer(mesure(horodatage = 1_000, desactives = 0, libre = 400))
        depot.enregistrer(mesure(horodatage = 2_000, desactives = 30, libre = 700))
        depot.enregistrer(mesure(horodatage = 3_000, desactives = 56, libre = 900))

        val historique = depot.historique.value
        assertEquals(1_000L, historique.reference?.horodatage)
        assertEquals(3_000L, historique.derniere?.horodatage)
        assertEquals(56, historique.paquetsDesactivesEnPlus)
        assertEquals(500L, historique.memoireLibreEnPlusMo)
    }

    @Test
    fun `the gain is read back after the app restarts`() = runTest {
        val cible = fichier()
        val premiere = MesuresRepository(cible)
        premiere.charger()
        premiere.enregistrer(mesure(horodatage = 1_000, desactives = 0, libre = 400))
        premiere.enregistrer(mesure(horodatage = 2_000, desactives = 56, libre = 900))

        // A later session: the reason the baseline is persisted.
        val seconde = MesuresRepository(cible)
        seconde.charger()

        assertEquals(1_000L, seconde.historique.value.reference?.horodatage)
        assertEquals(56, seconde.historique.value.paquetsDesactivesEnPlus)
    }

    @Test
    fun `a single measurement has nothing to compare to`() = runTest {
        val depot = MesuresRepository(fichier())
        depot.charger()
        depot.enregistrer(mesure(horodatage = 1_000))

        assertFalse(depot.historique.value.comparable)
        assertEquals(0, depot.historique.value.paquetsDesactivesEnPlus)
    }

    @Test
    fun `an empty snapshot is not recorded`() = runTest {
        val depot = MesuresRepository(fichier())
        depot.charger()

        // An unreachable TV reads as all zeros, which would overwrite the baseline.
        depot.enregistrer(Mesure(horodatage = 9_000, 0, 0, 0, 0))

        assertTrue(depot.historique.value.reference == null)
    }

    @Test
    fun `a memory loss is shown as is`() = runTest {
        val depot = MesuresRepository(fichier())
        depot.charger()
        depot.enregistrer(mesure(horodatage = 1_000, libre = 900))
        depot.enregistrer(mesure(horodatage = 2_000, libre = 600))

        assertEquals(-300L, depot.historique.value.memoireLibreEnPlusMo)
    }

    @Test
    fun `resetting the baseline starts from the current state`() = runTest {
        val depot = MesuresRepository(fichier())
        depot.charger()
        depot.enregistrer(mesure(horodatage = 1_000, desactives = 0))
        depot.enregistrer(mesure(horodatage = 2_000, desactives = 56))

        depot.redefinirReference()

        assertEquals(2_000L, depot.historique.value.reference?.horodatage)
        assertEquals(0, depot.historique.value.paquetsDesactivesEnPlus)
        assertFalse(depot.historique.value.comparable)
    }
}
