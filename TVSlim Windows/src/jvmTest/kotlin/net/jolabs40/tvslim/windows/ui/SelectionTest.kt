package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.device.EtatPaquet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La sélection décide de ce qui va être désactivé sur un téléviseur : elle ne doit jamais cocher un
 * paquet qui n'est pas actif. Mêmes cas que le compagnon Android, sur l'état de la fenêtre.
 */
class SelectionTest {

    private fun entree(paquet: String, categorie: String = "bloatware_tcl", nom: String = paquet) =
        EntreePaquet(paquet = paquet, nom = nom, description = "", categorie = categorie)

    private fun etat(vararg lignes: Pair<String, EtatPaquet>) = EtatApp(
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
        val depart = EtatApp(
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
        val depart = EtatApp(
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
        val depart = EtatApp(
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

    @Test
    fun `la liste n'affiche ni les absents ni ce que le filtre ecarte`() {
        val depart = etat(
            "com.actif" to EtatPaquet.ACTIF,
            "com.eteint" to EtatPaquet.DESACTIVE,
            "com.absent" to EtatPaquet.ABSENT,
        )

        assertEquals(listOf("com.actif", "com.eteint"), depart.affichees.map { it.entree.paquet })
        assertEquals(
            listOf("com.eteint"),
            depart.copy(filtre = Filtre.DESACTIVES).affichees.map { it.entree.paquet },
        )
        assertEquals(1, depart.nombreActifs)
        assertEquals(1, depart.nombreDesactives)
    }

    @Test
    fun `le volet de detail ne parle pas d'un paquet que la recherche a masque`() {
        val depart = etat("com.netflix" to EtatPaquet.ACTIF, "com.tcl.pub" to EtatPaquet.ACTIF)
            .copy(paquetDetaille = "com.netflix")

        assertEquals("com.netflix", depart.ligneDetaillee?.entree?.paquet)
        assertNull(depart.copy(recherche = "tcl").ligneDetaillee)
    }
}
