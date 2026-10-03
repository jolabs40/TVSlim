package net.jolabs40.tvslim.fichiers

import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La lecture d'un dossier : la commande, et sa réponse telle que la TCL la rend (relevée le 2026-10-03). */
class LectureDossierTest {

    /** Extrait de `/` sur la TCL : des dossiers, des liens vers des dossiers, et `d`, un lien vers `/sdcard`. */
    private val racineTcl = """
        E|41ed|27|1230768000|acct
        E|a1a4|11|1230768000|bin
        E|41f8|4096|2|cache
        E|a1a4|17|1230768000|d
        E|41f9|4096|1790426962|data
        E|a1a4|21|1230768000|init.environ.rc
        D|bin
        D|d
    """.trimIndent()

    @Test
    fun `un lien vers un dossier se parcourt comme un dossier`() {
        val entrees = LecteurDossier.entrees(racineTcl).associateBy { it.nom }

        assertEquals(NatureEntree.DOSSIER, entrees.getValue("bin").nature)
        assertTrue(entrees.getValue("bin").lien)
        assertEquals(NatureEntree.DOSSIER, entrees.getValue("data").nature)
        assertFalse(entrees.getValue("data").lien)
        // Un lien dont la cible n'est pas un dossier — ou n'existe pas — reste un fichier.
        assertEquals(NatureEntree.FICHIER, entrees.getValue("init.environ.rc").nature)
    }

    @Test
    fun `les dossiers d'abord, puis les fichiers, sans egard a la casse`() {
        val sortie = """
            E|81b0|1412089|1789490078|rest.mp4
            E|45f8|4096|1751999274|Movies
            E|81b0|192902|1789249937|a.png
            E|45f9|4096|1751999271|android
            E|81b0|0|1789298639|B.png
        """.trimIndent()

        assertEquals(
            listOf("android", "Movies", "a.png", "B.png", "rest.mp4"),
            LecteurDossier.entrees(sortie).map { it.nom },
        )
    }

    @Test
    fun `taille et date se lisent, la date en millisecondes`() {
        val video = LecteurDossier.entrees("E|81b0|1412089|1789490078|rest.mp4").single()

        assertEquals(NatureEntree.FICHIER, video.nature)
        assertEquals(1_412_089L, video.taille)
        assertEquals(1_789_490_078_000L, video.date)
    }

    @Test
    fun `un nom garde ses espaces et ses barres verticales`() {
        val entree = LecteurDossier.entrees("E|81b0|10|1|Film | partie 2 .mkv").single()

        assertEquals("Film | partie 2 .mkv", entree.nom)
    }

    @Test
    fun `ce qui n'est ni un dossier ni un fichier est dit autre, et les lignes etrangeres sont ignorees`() {
        val sortie = """
            stat: '.*': No such file or directory
            E|21b6|0|1|null
            E|41ed|4096|1|.
            E|41ed|4096|1|..
            E|zz|1|1|illisible
        """.trimIndent()

        assertEquals(listOf("null" to NatureEntree.AUTRE), LecteurDossier.entrees(sortie).map { it.nom to it.nature })
    }

    @Test
    fun `les codes de la commande disent introuvable, refuse ou echec`() {
        assertEquals(LectureDossier.Introuvable("/x"), LecteurDossier.lire("/x", ResultatShell(2, "")))
        assertEquals(LectureDossier.Refusee("/data"), LecteurDossier.lire("/data", ResultatShell(3, "")))
        assertEquals(
            LectureDossier.Echouee("/sdcard", "Aucun téléviseur connecté."),
            LecteurDossier.lire("/sdcard", ResultatShell.indisponible("Aucun téléviseur connecté.")),
        )
        assertEquals(LectureDossier.Lue("/vide", emptyList()), LecteurDossier.lire("/vide", ResultatShell(0, "")))
    }

    @Test
    fun `le chemin entre cite dans la commande`() {
        val commande = LecteurDossier.commande("/sdcard/l'été")

        assertTrue(commande.startsWith("[ -d '/sdcard/l'\\''été' ] || exit 2; cd '/sdcard/l'\\''été' "))
        assertTrue("stat -c 'E|%f|%s|%Y|%n'" in commande)
    }

    @Test
    fun `les volumes amovibles sont tout ce que storage porte d'autre`() {
        assertEquals(listOf("1234-ABCD"), LecteurDossier.volumes("emulated\nself\n1234-ABCD\n"))
        assertEquals(emptyList<String>(), LecteurDossier.volumes("emulated\nself"))
    }

    @Test
    fun `les volumes s'inserent entre les dossiers communs et les dossiers techniques`() {
        val natures = Raccourci.avecVolumes(listOf("1234-ABCD")).map { it.nature }

        assertEquals(
            listOf(
                NatureRaccourci.INTERNE, NatureRaccourci.TELECHARGEMENTS, NatureRaccourci.FILMS,
                NatureRaccourci.MUSIQUE, NatureRaccourci.IMAGES, NatureRaccourci.VOLUME,
                NatureRaccourci.TEMPORAIRE, NatureRaccourci.RACINE,
            ),
            natures,
        )
        assertEquals("/storage/1234-ABCD", Raccourci.avecVolumes(listOf("1234-ABCD"))[5].chemin)
    }
}
