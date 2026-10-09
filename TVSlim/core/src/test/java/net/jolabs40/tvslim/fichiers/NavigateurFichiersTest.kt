package net.jolabs40.tvslim.fichiers

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.EnvoyeurFichiers
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Browsing and uploading to the TV: what is sent, in which order, and what stops it. */
class NavigateurFichiersTest {

    /** Fake TV: knows a few folders, records commands and uploads, and refuses the given paths. */
    private class Televiseur(
        private val dossiers: Map<String, String> = mapOf("/sdcard/Movies" to ""),
        private val refuses: Set<String> = emptySet(),
        private val coupeA: String? = null,
        private val reponseMkdir: ResultatShell = ResultatShell(0, ""),
    ) : ExecuteurCommande, EnvoyeurFichiers {
        val commandes = mutableListOf<String>()
        val envois = mutableListOf<Pair<String, String>>()
        var surEnvoi: (String) -> Unit = {}

        override suspend fun executer(commande: String): ResultatShell {
            commandes += commande
            if (commande.startsWith("mkdir -p")) return reponseMkdir
            if (commande.startsWith("ls /storage")) return ResultatShell(0, "emulated\nself\nCLE-USB")
            val chemin = Regex("""^\[ -d '([^']*)' ]""").find(commande)?.groupValues?.get(1)
            if (chemin != null) return dossiers[chemin]?.let { ResultatShell(0, it) } ?: ResultatShell(2, "")
            if (commande.contains("mkdir '")) return ResultatShell(0, "")
            return ResultatShell(127, "inconnue")
        }

        override suspend fun envoyer(
            source: InputStream,
            taille: Long,
            chemin: String,
            date: Long,
            annule: () -> Boolean,
            surEnvoi: (envoye: Long) -> Unit,
        ): ResultatShell {
            val contenu = source.use { String(it.readBytes()) }
            this.surEnvoi(chemin)
            if (annule()) return ResultatShell.indisponible("annulé")
            if (chemin == coupeA) return ResultatShell.indisponible("Connection reset")
            if (chemin in refuses) return ResultatShell(1, "couldn't create file: Permission denied")
            surEnvoi(taille)
            envois += chemin to contenu
            return ResultatShell(0, "")
        }
    }

    private class Fichier(override val chemin: String, private val contenu: String = chemin) : FichierLocal {
        override val taille: Long = contenu.length.toLong()
        override val date: Long = 0L
        override fun ouvrir(): InputStream = ByteArrayInputStream(contenu.toByteArray())
    }

    private class Illisible(override val chemin: String) : FichierLocal {
        override val taille = 10L
        override val date = 0L
        override fun ouvrir(): InputStream = throw IOException("Accès refusé")
    }

    private fun plan(lot: LotLocal, destination: String = "/sdcard/Movies") = PlanDepot(destination, lot, emptyList())

    @Test
    fun `an uploaded folder creates its folders, empty ones included, before its files`() = runTest {
        val tv = Televiseur()
        val lot = LotLocal(
            fichiers = listOf(Fichier("Vacances/2024/plage.jpg"), Fichier("Vacances/notes.txt")),
            dossiers = listOf("Vacances", "Vacances/2024", "Vacances/vide"),
        )

        val resultat = NavigateurFichiers(tv, tv).deposer(plan(lot))

        assertTrue(resultat.complet)
        assertEquals(
            "mkdir -p '/sdcard/Movies/Vacances' '/sdcard/Movies/Vacances/2024' '/sdcard/Movies/Vacances/vide'",
            tv.commandes.single(),
        )
        assertEquals(
            listOf("/sdcard/Movies/Vacances/2024/plage.jpg", "/sdcard/Movies/Vacances/notes.txt"),
            tv.envois.map { it.first },
        )
        assertEquals("Vacances/notes.txt", tv.envois[1].second)
    }

    @Test
    fun `a refused file does not stop the next ones`() = runTest {
        val tv = Televiseur(refuses = setOf("/sdcard/Movies/b.mkv"))
        val lot = LotLocal(listOf(Fichier("a.mkv"), Fichier("b.mkv"), Illisible("c.mkv"), Fichier("d.mkv")))

        val resultat = NavigateurFichiers(tv, tv).deposer(plan(lot))

        assertEquals(2, resultat.envoyes)
        assertEquals(4, resultat.nombre)
        assertEquals(
            listOf(
                EchecDepot("b.mkv", "couldn't create file: Permission denied"),
                EchecDepot("c.mkv", "Accès refusé"),
            ),
            resultat.echecs,
        )
        assertFalse(resultat.complet)
        // No folder to create, so no command is sent, only the files.
        assertTrue(tv.commandes.isEmpty())
    }

    @Test
    fun `a lost connection stops everything`() = runTest {
        val tv = Televiseur(coupeA = "/sdcard/Movies/b.mkv")
        val lot = LotLocal(listOf(Fichier("a.mkv"), Fichier("b.mkv"), Fichier("c.mkv")))

        val resultat = NavigateurFichiers(tv, tv).deposer(plan(lot))

        assertTrue(resultat.interrompu)
        assertEquals(1, resultat.envoyes)
        assertEquals(listOf("/sdcard/Movies/a.mkv"), tv.envois.map { it.first })
    }

    @Test
    fun `cancelling stops the current upload and the next ones`() = runTest {
        val tv = Televiseur()
        var annule = false
        tv.surEnvoi = { chemin -> if (chemin.endsWith("b.mkv")) annule = true }
        val lot = LotLocal(listOf(Fichier("a.mkv"), Fichier("b.mkv"), Fichier("c.mkv")))

        val resultat = NavigateurFichiers(tv, tv).deposer(plan(lot), annule = { annule })

        assertTrue(resultat.annule)
        assertFalse(resultat.interrompu)
        assertEquals(1, resultat.envoyes)
        assertTrue(resultat.echecs.isEmpty())
    }

    @Test
    fun `folders that cannot be created stop the upload before the first file`() = runTest {
        val tv = Televiseur(reponseMkdir = ResultatShell(1, "mkdir: '/x/a': Permission denied"))
        val lot = LotLocal(listOf(Fichier("a/b.txt")))

        val resultat = NavigateurFichiers(tv, tv).deposer(plan(lot, "/x"))

        assertEquals(listOf(EchecDepot("/x", "mkdir: '/x/a': Permission denied")), resultat.echecs)
        assertTrue(tv.envois.isEmpty())
    }

    @Test
    fun `progress tracks bytes across the whole batch`() = runTest {
        val tv = Televiseur()
        val lot = LotLocal(listOf(Fichier("a", "1234"), Fichier("b", "123456")))
        val avancees = mutableListOf<AvanceeDepot>()

        NavigateurFichiers(tv, tv).deposer(plan(lot), surAvancee = { avancees += it })

        assertEquals(
            listOf(
                AvanceeDepot("a", 1, 2, 0, 10),
                AvanceeDepot("a", 1, 2, 4, 10),
                AvanceeDepot("b", 2, 2, 4, 10),
                AvanceeDepot("b", 2, 2, 10, 10),
            ),
            avancees,
        )
    }

    @Test
    fun `the check lists what already exists and refuses a file in place of a folder`() = runTest {
        val existant = "E|45f8|4096|1|Vacances\nE|81b0|5|1|film.mkv"
        val tv = Televiseur(dossiers = mapOf("/sdcard/Movies" to existant))
        val navigateur = NavigateurFichiers(tv, tv)

        val pret = navigateur.examiner(
            LotLocal(listOf(Fichier("Vacances/a.jpg"), Fichier("film.mkv"), Fichier("neuf.mkv"))),
            "/sdcard/Movies/",
        )
        assertEquals(listOf("Vacances", "film.mkv"), (pret as ExamenDepot.Pret).plan.existants)
        assertEquals("/sdcard/Movies", pret.plan.destination)

        val refuse = navigateur.examiner(LotLocal(listOf(Fichier("Vacances"))), "/sdcard/Movies")
        assertEquals(ExamenDepot.Refuse(RefusDepot.NATURE_DIFFERENTE, listOf("Vacances")), refuse)
    }

    @Test
    fun `the check refuses an empty batch, a broken name and an unreadable folder without sending anything`() = runTest {
        val tv = Televiseur()
        val navigateur = NavigateurFichiers(tv, tv)

        assertEquals(ExamenDepot.Refuse(RefusDepot.VIDE), navigateur.examiner(LotLocal(emptyList()), "/sdcard/Movies"))
        assertEquals(
            ExamenDepot.Refuse(RefusDepot.NOM_INVALIDE, listOf("a\nb.txt")),
            navigateur.examiner(LotLocal(listOf(Fichier("a\nb.txt"))), "/sdcard/Movies"),
        )
        assertEquals(
            ExamenDepot.Refuse(RefusDepot.DESTINATION_ILLISIBLE),
            navigateur.examiner(LotLocal(listOf(Fichier("a.txt"))), "/absent"),
        )
        assertTrue(tv.envois.isEmpty())
    }

    @Test
    fun `a folder is created once, and a taken name is reported`() = runTest {
        val tv = object : ExecuteurCommande, EnvoyeurFichiers {
            val commandes = mutableListOf<String>()
            override suspend fun executer(commande: String): ResultatShell {
                commandes += commande
                return if (commande.contains("Pris")) ResultatShell(4, "") else ResultatShell(0, "")
            }

            override suspend fun envoyer(
                source: InputStream, taille: Long, chemin: String, date: Long,
                annule: () -> Boolean, surEnvoi: (envoye: Long) -> Unit,
            ) = error("rien ne s'envoie")
        }
        val navigateur = NavigateurFichiers(tv, tv)

        assertEquals(CreationDossier(IssueCreation.CREE, "Séries"), navigateur.creerDossier("/sdcard/", " Séries "))
        assertEquals("[ -e '/sdcard/Séries' ] && exit 4; mkdir '/sdcard/Séries'", tv.commandes.single())
        assertEquals(IssueCreation.EXISTE, navigateur.creerDossier("/sdcard", "Pris").issue)
        assertEquals(IssueCreation.NOM_INVALIDE, navigateur.creerDossier("/sdcard", "a/b").issue)
        assertEquals(2, tv.commandes.size)
    }

    @Test
    fun `the explorer reads on start and uploads into the current folder`() = runTest {
        val tv = Televiseur(dossiers = mapOf("/sdcard" to "E|45f8|4096|1|Movies", "/sdcard/Movies" to ""))
        val signaux = mutableListOf<SignalFichiers>()
        val explorateur = ExplorateurFichiers(NavigateurFichiers(tv, tv), this, signaux::add)

        explorateur.demarrer()
        advanceUntilIdle()
        assertEquals(listOf("Movies"), explorateur.etat.value.entrees.map { it.nom })
        assertEquals("/storage/CLE-USB", explorateur.etat.value.raccourcis.first { it.nature == NatureRaccourci.VOLUME }.chemin)

        explorateur.ouvrir("/sdcard/Movies")
        explorateur.examiner { LotLocal(listOf(Fichier("film.mkv"))) }
        advanceUntilIdle()
        assertNotNull(explorateur.etat.value.confirmation)
        assertTrue("Rien ne part avant la confirmation", tv.envois.isEmpty())

        explorateur.confirmer()
        advanceUntilIdle()
        assertEquals(listOf("/sdcard/Movies/film.mkv"), tv.envois.map { it.first })
        assertNull(explorateur.etat.value.avancee)
        assertTrue((signaux.single() as SignalFichiers.Depot).resultat.complet)
    }

    @Test
    fun `forgetting the tv discards results from before`() = runTest {
        val tv = Televiseur(dossiers = mapOf("/sdcard" to "E|45f8|4096|1|Movies"))
        val explorateur = ExplorateurFichiers(NavigateurFichiers(tv, tv), this) {}

        explorateur.demarrer()
        explorateur.oublier()
        advanceUntilIdle()

        assertNull(explorateur.etat.value.lecture)
        assertFalse(explorateur.etat.value.chargement)
    }
}
