package net.jolabs40.tvslim.remote.ui

import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.device.EtatPaquet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection decides what gets disabled on a TV, so it must never check an inactive package:
 * that would offer to disable something already disabled, or missing from the device.
 */
class SelectionTest {

    private fun entree(paquet: String, categorie: String = "bloatware_tcl") = EntreePaquet(
        paquet = paquet,
        nom = paquet,
        description = "",
        categorie = categorie,
    )

    private fun etat(vararg lignes: Pair<String, EtatPaquet>) = EtatRemote(
        lignes = lignes.map { (paquet, etat) -> LignePaquet(entree(paquet), etat) },
    )

    private fun profil(vararg categories: String) =
        Profil(id = "doux", nom = "Doux", description = "", categories = categories.toList())

    @Test
    fun `toggling checks then unchecks an active package`() {
        val depart = etat("com.tcl.pub" to EtatPaquet.ACTIF)

        val coche = depart.avecBascule("com.tcl.pub")
        assertTrue(coche.lignes.single().selectionne)

        assertFalse(coche.avecBascule("com.tcl.pub").lignes.single().selectionne)
    }

    @Test
    fun `an already disabled or missing package cannot be checked`() {
        val depart = etat(
            "com.deja.eteint" to EtatPaquet.DESACTIVE,
            "com.pas.installe" to EtatPaquet.ABSENT,
        )

        val apres = depart.avecBascule("com.deja.eteint").avecBascule("com.pas.installe")

        assertTrue(apres.selection.isEmpty())
    }

    @Test
    fun `a profile only checks its category, and only active packages`() {
        val depart = EtatRemote(
            lignes = listOf(
                LignePaquet(entree("com.a", "bloatware_tcl"), EtatPaquet.ACTIF),
                LignePaquet(entree("com.b", "expert"), EtatPaquet.ACTIF),
                LignePaquet(entree("com.c", "bloatware_tcl"), EtatPaquet.DESACTIVE),
            ),
        )

        val apres = depart.avecProfil(profil("bloatware_tcl"))

        assertEquals(listOf("com.a"), apres.selection.map { it.entree.paquet })
    }

    @Test
    fun `a profile adds to the current selection instead of replacing it`() {
        val depart = EtatRemote(
            lignes = listOf(
                LignePaquet(entree("com.a", "expert"), EtatPaquet.ACTIF, selectionne = true),
                LignePaquet(entree("com.b", "bloatware_tcl"), EtatPaquet.ACTIF),
            ),
        )

        val apres = depart.avecProfil(profil("bloatware_tcl"))

        assertEquals(listOf("com.a", "com.b"), apres.selection.map { it.entree.paquet })
    }

    @Test
    fun `a profile never checks an untested entry, which can still be checked by hand`() {
        val depart = EtatRemote(
            lignes = listOf(
                LignePaquet(entree("com.a"), EtatPaquet.ACTIF),
                LignePaquet(entree("org.droidtv.welcome").copy(eprouve = false), EtatPaquet.ACTIF),
            ),
        )

        val apres = depart.avecProfil(profil("bloatware_tcl"))

        assertEquals(listOf("com.a"), apres.selection.map { it.entree.paquet })
        assertEquals(
            listOf("com.a", "org.droidtv.welcome"),
            apres.avecBascule("org.droidtv.welcome").selection.map { it.entree.paquet },
        )
    }

    @Test
    fun `unchecking all leaves nothing selected`() {
        val depart = etat("com.a" to EtatPaquet.ACTIF, "com.b" to EtatPaquet.ACTIF)
            .avecBascule("com.a")
            .avecBascule("com.b")

        assertTrue(depart.sansSelection().selection.isEmpty())
    }
}
