package net.jolabs40.tvslim.fichiers

import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folder listing: the command, and its output as captured on the TCL. */
class LectureDossierTest {

    /** Excerpt of `/` on the TCL: folders, links to folders, and `d`, a link to `/sdcard`. */
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
    fun `a link to a folder is browsed like a folder`() {
        val entrees = LecteurDossier.entrees(racineTcl).associateBy { it.nom }

        assertEquals(NatureEntree.DOSSIER, entrees.getValue("bin").nature)
        assertTrue(entrees.getValue("bin").lien)
        assertEquals(NatureEntree.DOSSIER, entrees.getValue("data").nature)
        assertFalse(entrees.getValue("data").lien)
        // A link whose target is not a folder, or does not exist, stays a file.
        assertEquals(NatureEntree.FICHIER, entrees.getValue("init.environ.rc").nature)
    }

    @Test
    fun `folders come first, then files, ignoring case`() {
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
    fun `size and date are read, the date in milliseconds`() {
        val video = LecteurDossier.entrees("E|81b0|1412089|1789490078|rest.mp4").single()

        assertEquals(NatureEntree.FICHIER, video.nature)
        assertEquals(1_412_089L, video.taille)
        assertEquals(1_789_490_078_000L, video.date)
    }

    @Test
    fun `a name keeps its spaces and vertical bars`() {
        val entree = LecteurDossier.entrees("E|81b0|10|1|Film | partie 2 .mkv").single()

        assertEquals("Film | partie 2 .mkv", entree.nom)
    }

    @Test
    fun `anything neither folder nor file is other, and stray lines are ignored`() {
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
    fun `the command exit codes mean not found, denied or failed`() {
        assertEquals(LectureDossier.Introuvable("/x"), LecteurDossier.lire("/x", ResultatShell(2, "")))
        assertEquals(LectureDossier.Refusee("/data"), LecteurDossier.lire("/data", ResultatShell(3, "")))
        assertEquals(
            LectureDossier.Echouee("/sdcard", "Aucun téléviseur connecté."),
            LecteurDossier.lire("/sdcard", ResultatShell.indisponible("Aucun téléviseur connecté.")),
        )
        assertEquals(LectureDossier.Lue("/vide", emptyList()), LecteurDossier.lire("/vide", ResultatShell(0, "")))
    }

    @Test
    fun `the path is quoted in the command`() {
        val commande = LecteurDossier.commande("/sdcard/l'été")

        assertTrue(commande.startsWith("[ -d '/sdcard/l'\\''été' ] || exit 2; cd '/sdcard/l'\\''été' "))
        assertTrue("stat -c 'E|%f|%s|%Y|%n'" in commande)
    }

    @Test
    fun `removable volumes are everything else under storage`() {
        assertEquals(listOf("1234-ABCD"), LecteurDossier.volumes("emulated\nself\n1234-ABCD\n"))
        assertEquals(emptyList<String>(), LecteurDossier.volumes("emulated\nself"))
    }

    @Test
    fun `volumes go between the common folders and the technical ones`() {
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
