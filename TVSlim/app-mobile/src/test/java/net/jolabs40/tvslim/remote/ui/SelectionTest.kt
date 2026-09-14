package net.jolabs40.tvslim.remote.ui

import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.device.EtatPaquet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La sélection décide de ce qui va être désactivé sur un téléviseur : elle ne doit jamais
 * cocher un paquet qui n'est pas actif, sous peine de proposer d'éteindre ce qui l'est déjà —
 * ou pire, ce qui n'existe pas sur l'appareil.
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
    fun `basculer coche puis decoche un paquet actif`() {
        val depart = etat("com.tcl.pub" to EtatPaquet.ACTIF)

        val coche = depart.avecBascule("com.tcl.pub")
        assertTrue(coche.lignes.single().selectionne)

        assertFalse(coche.avecBascule("com.tcl.pub").lignes.single().selectionne)
    }

    @Test
    fun `un paquet deja desactive ou absent ne se coche pas`() {
        val depart = etat(
            "com.deja.eteint" to EtatPaquet.DESACTIVE,
            "com.pas.installe" to EtatPaquet.ABSENT,
        )

        val apres = depart.avecBascule("com.deja.eteint").avecBascule("com.pas.installe")

        assertTrue(apres.selection.isEmpty())
    }

    @Test
    fun `un profil ne coche que sa categorie, et seulement l actif`() {
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
    fun `un profil s ajoute a la selection en cours plutot que de la remplacer`() {
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
    fun `un profil ne coche jamais une entree non eprouvee, qui reste cochable a la main`() {
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
    fun `tout decocher ne laisse rien`() {
        val depart = etat("com.a" to EtatPaquet.ACTIF, "com.b" to EtatPaquet.ACTIF)
            .avecBascule("com.a")
            .avecBascule("com.b")

        assertTrue(depart.sansSelection().selection.isEmpty())
    }
}
