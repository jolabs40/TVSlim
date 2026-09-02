package net.jolabs40.tvslim.device

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lecture d'état à distance.
 *
 * Le premier test est né d'un vrai échec sur la TCL : les marqueurs commençaient par `#`, que
 * le shell traite comme un début de commentaire. La commande composite entière était avalée,
 * `pm` n'était jamais appelé, et le compagnon affichait un téléviseur vide sans la moindre
 * erreur — le pire des cas, un échec silencieux.
 */
class LecteurDistantTest {

    private class ExecuteurFixe(private val sortie: String, private val code: Int = 0) :
        ExecuteurCommande {
        var recue: String? = null
        override suspend fun executer(commande: String): ResultatShell {
            recue = commande
            return ResultatShell(code, sortie)
        }
    }

    @Test
    fun `la commande ne contient aucun mot ouvrant un commentaire shell`() {
        val motsCommentaire = LecteurDistant.COMMANDE
            .split(' ', ';')
            .map { it.trim() }
            .filter { it.startsWith("#") }

        assertTrue(
            "Ces mots feraient taire tout le reste de la ligne : $motsCommentaire",
            motsCommentaire.isEmpty(),
        )
    }

    @Test
    fun `chaque section est annoncee par son marqueur`() {
        val marqueurs = listOf("_D", "_E", "_P", "_M", "_H", "_L")
            .map { LecteurDistant.PREFIXE_MARQUEUR + it.removePrefix("_") }
        marqueurs.forEach { marqueur ->
            assertTrue(
                "La commande doit annoncer $marqueur",
                LecteurDistant.COMMANDE.contains("echo $marqueur"),
            )
        }
    }

    @Test
    fun `une sortie realiste est decoupee correctement`() = runTest {
        val sortie = """
            @@TVSLIM_D
            package:com.tcl.gallery
            package:com.netflix.ninja
            @@TVSLIM_E
            package:com.spocky.projengmenu
            package:net.jolabs40.tvslim
            @@TVSLIM_P
            TCL
            65C89K
            14
            tcl9618-user
            @@TVSLIM_M
            MemTotal:        2513404 kB
            MemAvailable:     628112 kB
            @@TVSLIM_H
            priority=0 preferredOrder=0 match=0x0 specificIndex=-1 isDefault=false
            com.spocky.projengmenu/.ui.home.MainActivity
            @@TVSLIM_L
            com.spocky.projengmenu/.ui.home.MainActivity
            com.google.android.apps.tv.launcherx/.home.HomeActivity
        """.trimIndent()

        val photo = LecteurDistant(ExecuteurFixe(sortie)).photographie(
            paquetsSurveilles = listOf("com.tcl.gallery", "com.spocky.projengmenu", "absent.ici"),
            paquetsDAccueil = setOf("com.google.android.apps.tv.launcherx"),
        )

        assertEquals(EtatPaquet.DESACTIVE, photo.etats["com.tcl.gallery"])
        assertEquals(EtatPaquet.ACTIF, photo.etats["com.spocky.projengmenu"])
        assertEquals(EtatPaquet.ABSENT, photo.etats["absent.ici"])

        assertEquals("TCL", photo.infos.marque)
        assertEquals("65C89K", photo.infos.modele)
        assertEquals("14", photo.infos.versionAndroid)
        assertEquals(2, photo.infos.paquetsDesactives)
        assertEquals(2, photo.infos.paquetsInstalles)
        assertEquals(2454, photo.infos.memoireTotaleMo)
        assertEquals("com.spocky.projengmenu", photo.infos.accueilActuel)

        // L'accueil d'usine ne compte pas comme un launcher de remplacement.
        assertEquals(listOf("com.spocky.projengmenu"), photo.infos.launchersTiers.map { it.paquet })
    }

    @Test
    fun `une commande en echec ne fabrique pas de fausses donnees`() = runTest {
        val photo = LecteurDistant(ExecuteurFixe("", code = 1)).photographie(
            paquetsSurveilles = listOf("com.tcl.gallery"),
            paquetsDAccueil = emptySet(),
        )

        assertEquals(InfosAppareil.VIDE, photo.infos)
        assertTrue(photo.etats.isEmpty())
    }

    @Test
    fun `une sortie vide ne passe pas pour un televiseur sans paquets`() = runTest {
        // Exactement ce que renvoyait la commande avalée par le commentaire : un succès vide.
        val photo = LecteurDistant(ExecuteurFixe("")).photographie(
            paquetsSurveilles = listOf("com.tcl.gallery"),
            paquetsDAccueil = emptySet(),
        )

        // Le paquet est déclaré absent, faute de mieux — mais rien ne doit laisser croire
        // qu'on a lu un téléviseur en bonne santé.
        assertEquals(EtatPaquet.ABSENT, photo.etats["com.tcl.gallery"])
        assertEquals(0, photo.infos.paquetsInstalles)
        assertFalse("Aucune propriété ne doit être inventée", photo.infos.modele.isNotBlank())
    }
}
