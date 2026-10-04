package net.jolabs40.tvslim.fichiers

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.EnvoyeurFichiers
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.RecepteurFichiers
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Copier vers l'ordinateur, effacer du téléviseur : ce qui part, ce qui arrive, ce qui s'arrête et ce qu'on refuse. */
class CopieSuppressionTest {

    /**
     * Un téléviseur bouchon : des fichiers et leur contenu, une réponse d'inventaire par dossier, et des commandes
     * de suppression retenues sans rien effacer.
     */
    private class Televiseur(
        private val contenus: Map<String, String> = emptyMap(),
        private val inventaires: Map<String, ResultatShell> = emptyMap(),
        private val refuses: Set<String> = emptySet(),
        private val coupeA: String? = null,
        private val reponseRm: ResultatShell = ResultatShell(0, ""),
    ) : ExecuteurCommande, EnvoyeurFichiers, RecepteurFichiers {
        val commandes = mutableListOf<String>()
        val recus = mutableListOf<String>()
        var surReception: (String) -> Unit = {}

        override suspend fun executer(commande: String): ResultatShell {
            commandes += commande
            if (commande.contains("rm -")) return reponseRm
            val chemin = Regex("""\[ -d '([^']*)' ] \|\| exit 2; cd""").find(commande)?.groupValues?.get(1)
            return chemin?.let { inventaires[it] } ?: ResultatShell(2, "")
        }

        override suspend fun envoyer(
            source: InputStream, taille: Long, chemin: String, date: Long,
            annule: () -> Boolean, surEnvoi: (envoye: Long) -> Unit,
        ) = error("rien ne s'envoie")

        override suspend fun recevoir(
            chemin: String, destination: OutputStream, taille: Long,
            annule: () -> Boolean, surRecu: (recu: Long) -> Unit,
        ): ResultatShell {
            surReception(chemin)
            if (annule()) return ResultatShell.indisponible("annulé")
            if (chemin == coupeA) return ResultatShell.indisponible("Connection reset")
            if (chemin in refuses) return ResultatShell(1, "open failed: Permission denied")
            val contenu = contenus[chemin] ?: return ResultatShell(1, "open failed: No such file or directory")
            // Une partie, puis le reste : ce qui n'est pas validé ne doit jamais arriver.
            destination.write(contenu.toByteArray())
            surRecu(contenu.length.toLong())
            recus += chemin
            return ResultatShell(0, "")
        }
    }

    /** Un dossier du disque, en mémoire : seul ce qui est validé y arrive. */
    private class Disque(
        val existants: Set<String> = emptySet(),
        private val refuses: Set<String> = emptySet(),
    ) : CibleLocale {
        val dossiers = mutableListOf<String>()
        val fichiers = linkedMapOf<String, Pair<String, Long>>()
        var ouverts = 0
        var fermes = 0

        override fun decrire(chemin: String) = if (chemin.isEmpty()) "C:\\Copies" else "C:\\Copies\\" + chemin.replace('/', '\\')
        override fun existe(chemin: String) = chemin in existants
        override fun creerDossier(chemin: String) {
            if (chemin in refuses) throw IOException("Accès refusé")
            dossiers += chemin
        }

        override fun ecrire(chemin: String): EcritureLocale {
            if (chemin in refuses) throw IOException("Nom de fichier incorrect")
            ouverts++
            return object : EcritureLocale {
                override val flux = ByteArrayOutputStream()
                override fun valider(date: Long) {
                    fichiers[chemin] = flux.toString(Charsets.UTF_8) to date
                }

                override fun close() {
                    fermes++
                }
            }
        }
    }

    private val films = EntreeDistante("Films", NatureEntree.DOSSIER, 4096, 0L)

    /** Ce que la TCL rend pour un dossier : `.` d'abord, des sous-dossiers, un vide, des noms à espaces et `|`. */
    private val inventaireFilms = ResultatShell(
        0,
        """
            D|0|1790000000|.
            D|0|1790000000|./Séries/Saison 1
            D|0|1790000000|./Séries
            D|0|1790000000|./vide
            F|12|1790000001|./bande|annonce.mp4
            F|3|1790000002|./Séries/Saison 1/e01.mkv
            find: ./perdu: Permission denied
        """.trimIndent(),
    )

    @Test
    fun `l'inventaire range les dossiers parents d'abord, sans le dossier lui-meme`() {
        val inventaire = InventaireDossier.inventaire(inventaireFilms.sortie)

        assertEquals(listOf("Séries", "vide", "Séries/Saison 1"), inventaire.dossiers)
        assertEquals(
            listOf(
                FichierInventaire("bande|annonce.mp4", 12, 1_790_000_001_000L),
                FichierInventaire("Séries/Saison 1/e01.mkv", 3, 1_790_000_002_000L),
            ),
            inventaire.fichiers,
        )
        assertEquals(15L, inventaire.taille)
    }

    @Test
    fun `les commandes citent leurs chemins, et seul un dossier monte la garde`() {
        assertEquals("rm -f '/sdcard/l'\\''été.mkv'", InventaireDossier.commandeSuppression("/sdcard/l'été.mkv", NatureSuppression.FICHIER))
        assertEquals("rm -f '/data/local/tmp/lien'", InventaireDossier.commandeSuppression("/data/local/tmp/lien", NatureSuppression.LIEN))

        val dossier = InventaireDossier.commandeSuppression("/sdcard/Films", NatureSuppression.DOSSIER)
        assertTrue(dossier.startsWith("c=\$(readlink -f '/sdcard/Films');"))
        assertTrue(dossier.contains("/storage/emulated/0 /storage/self/primary"))
        assertTrue(dossier.endsWith("exit 5;; esac; done; rm -rf '/sdcard/Films'"))

        assertFalse(InventaireDossier.commande("/sdcard/Films", garde = false).contains("readlink"))
        assertTrue(InventaireDossier.commande("/sdcard/Films", garde = true).startsWith("c=\$(readlink -f '/sdcard/Films');"))
    }

    @Test
    fun `un fichier se copie sous le nom choisi, sans rien lire de plus`() = runTest {
        val tv = Televiseur(contenus = mapOf("/sdcard/Movies/film.mkv" to "image"))
        val disque = Disque()
        val navigateur = NavigateurFichiers(tv, tv, tv)
        val entree = EntreeDistante("film.mkv", NatureEntree.FICHIER, 5, 1_000L)

        val plan = (navigateur.preparerRapatriement("/sdcard/Movies/", entree, disque, "copie.mkv") as ExamenRapatriement.Pret).plan
        assertTrue(tv.commandes.isEmpty())
        assertEquals("C:\\Copies", plan.destination)

        val resultat = navigateur.rapatrier(plan)

        assertTrue(resultat.complet)
        assertEquals(SensTransfert.RECEPTION, resultat.sens)
        assertEquals(mapOf("copie.mkv" to ("image" to 1_000L)), disque.fichiers)
        assertTrue(disque.dossiers.isEmpty())
    }

    @Test
    fun `un dossier se lit en entier, puis arrive avec ses dossiers vides`() = runTest {
        val tv = Televiseur(
            contenus = mapOf(
                "/sdcard/Films/bande|annonce.mp4" to "bande-annonce",
                "/sdcard/Films/Séries/Saison 1/e01.mkv" to "e01",
            ),
            inventaires = mapOf("/sdcard/Films" to inventaireFilms),
        )
        val disque = Disque(existants = setOf("Films"))
        val navigateur = NavigateurFichiers(tv, tv, tv)

        val plan = (navigateur.preparerRapatriement("/sdcard", films, disque, "Films") as ExamenRapatriement.Pret).plan

        assertTrue(plan.existant)
        assertEquals("C:\\Copies\\Films", plan.destination)
        assertEquals(listOf("Films", "Films/Séries", "Films/vide", "Films/Séries/Saison 1"), plan.dossiers)
        assertEquals(
            listOf("/sdcard/Films/bande|annonce.mp4", "/sdcard/Films/Séries/Saison 1/e01.mkv"),
            plan.fichiers.map { it.distant },
        )

        val resultat = navigateur.rapatrier(plan)

        assertTrue(resultat.complet)
        assertEquals(plan.dossiers, disque.dossiers)
        assertEquals(listOf("Films/bande|annonce.mp4", "Films/Séries/Saison 1/e01.mkv"), disque.fichiers.keys.toList())
        assertEquals("C:\\Copies\\Films", resultat.destination)
    }

    @Test
    fun `un dossier illisible ne se copie pas`() = runTest {
        val tv = Televiseur(inventaires = mapOf("/data" to ResultatShell(3, "")))
        val navigateur = NavigateurFichiers(tv, tv, tv)
        val data = EntreeDistante("data", NatureEntree.DOSSIER, 4096, 0L)

        assertEquals(ExamenRapatriement.Illisible(RefusLecture.REFUSE), navigateur.preparerRapatriement("/", data, Disque(), "data"))
        assertEquals(
            ExamenRapatriement.Illisible(RefusLecture.INTROUVABLE),
            navigateur.preparerRapatriement("/sdcard", films, Disque(), "Films"),
        )
    }

    @Test
    fun `un refus du televiseur ou du disque n'arrete pas les suivants, et rien d'inacheve n'arrive`() = runTest {
        val tv = Televiseur(
            contenus = mapOf("/sdcard/a" to "a", "/sdcard/c" to "c", "/sdcard/d" to "d"),
            refuses = setOf("/sdcard/b"),
        )
        val disque = Disque(refuses = setOf("c"))
        val plan = PlanRapatriement(
            source = "/sdcard",
            cible = disque,
            nom = "x",
            dossier = false,
            fichiers = listOf("a", "b", "c", "d").map { FichierDistant("/sdcard/$it", it, 1, 0L) },
        )

        val resultat = NavigateurFichiers(tv, tv, tv).rapatrier(plan)

        assertEquals(2, resultat.envoyes)
        assertEquals(
            listOf(EchecDepot("b", "open failed: Permission denied"), EchecDepot("c", "Nom de fichier incorrect")),
            resultat.echecs,
        )
        assertEquals(listOf("a", "d"), disque.fichiers.keys.toList())
        assertEquals("Chaque écriture ouverte est refermée", disque.ouverts, disque.fermes)
    }

    @Test
    fun `une connexion perdue arrete la copie, et annuler aussi`() = runTest {
        val contenus = mapOf("/sdcard/a" to "a", "/sdcard/b" to "b", "/sdcard/c" to "c")
        val fichiers = listOf("a", "b", "c").map { FichierDistant("/sdcard/$it", it, 1, 0L) }

        val coupe = Disque()
        val tvCoupee = Televiseur(contenus = contenus, coupeA = "/sdcard/b")
        val interrompu = NavigateurFichiers(tvCoupee, tvCoupee, tvCoupee)
            .rapatrier(PlanRapatriement("/sdcard", coupe, "x", false, fichiers))
        assertTrue(interrompu.interrompu)
        assertEquals(listOf("a"), coupe.fichiers.keys.toList())

        val arrete = Disque()
        val tv = Televiseur(contenus = contenus)
        var annule = false
        tv.surReception = { if (it.endsWith("b")) annule = true }
        val annulee = NavigateurFichiers(tv, tv, tv)
            .rapatrier(PlanRapatriement("/sdcard", arrete, "x", false, fichiers), annule = { annule })
        assertTrue(annulee.annule)
        assertEquals(1, annulee.envoyes)
        assertEquals(listOf("a"), arrete.fichiers.keys.toList())
    }

    @Test
    fun `un dossier refuse par le disque arrete la copie avant le premier fichier`() = runTest {
        val tv = Televiseur(contenus = mapOf("/sdcard/Films/a" to "a"))
        val disque = Disque(refuses = setOf("Films"))
        val plan = PlanRapatriement(
            "/sdcard/Films", disque, "Films", true,
            listOf(FichierDistant("/sdcard/Films/a", "Films/a", 1, 0L)), dossiers = listOf("Films"),
        )

        val resultat = NavigateurFichiers(tv, tv, tv).rapatrier(plan)

        assertEquals(listOf(EchecDepot("Films", "Accès refusé")), resultat.echecs)
        assertTrue(tv.recus.isEmpty())
    }

    @Test
    fun `la suppression d'un dossier dit ce qu'il contient, celle d'un lien n'emporte que lui`() = runTest {
        val tv = Televiseur(inventaires = mapOf("/sdcard/Films" to inventaireFilms))
        val navigateur = NavigateurFichiers(tv, tv)

        val dossier = navigateur.preparerSuppression("/sdcard", films)
        assertEquals(
            ExamenSuppression.Pret(PlanSuppression("/sdcard/Films", NatureSuppression.DOSSIER, fichiers = 2, dossiers = 3, taille = 15)),
            dossier,
        )
        assertTrue("La lecture monte la garde", tv.commandes.single().startsWith("c=\$(readlink -f '/sdcard/Films');"))

        val lien = EntreeDistante("sdcard", NatureEntree.DOSSIER, 21, 0L, lien = true)
        assertEquals(
            ExamenSuppression.Pret(PlanSuppression("/sdcard", NatureSuppression.LIEN)),
            navigateur.preparerSuppression("/", lien),
        )
        val fichier = EntreeDistante("a.mkv", NatureEntree.FICHIER, 42, 0L)
        assertEquals(
            ExamenSuppression.Pret(PlanSuppression("/sdcard/a.mkv", NatureSuppression.FICHIER, fichiers = 1, taille = 42)),
            navigateur.preparerSuppression("/sdcard", fichier),
        )
        assertEquals("Ni le lien ni le fichier ne demandent de lecture", 1, tv.commandes.size)
    }

    @Test
    fun `un stockage entier ne s'efface pas`() = runTest {
        val tv = Televiseur(inventaires = mapOf("/storage/emulated" to ResultatShell(5, "")), reponseRm = ResultatShell(5, ""))
        val navigateur = NavigateurFichiers(tv, tv)
        val emulated = EntreeDistante("emulated", NatureEntree.DOSSIER, 4096, 0L)

        assertEquals(ExamenSuppression.Protege, navigateur.preparerSuppression("/storage", emulated))
        assertEquals(
            SuppressionEntree(IssueSuppression.PROTEGE, "emulated"),
            navigateur.supprimer(PlanSuppression("/storage/emulated", NatureSuppression.DOSSIER)),
        )
    }

    @Test
    fun `un refus du televiseur se dit tel quel`() = runTest {
        val tv = Televiseur(reponseRm = ResultatShell(1, "rm: /system/x: Read-only file system"))

        val issue = NavigateurFichiers(tv, tv).supprimer(PlanSuppression("/system/x", NatureSuppression.FICHIER))

        assertEquals(SuppressionEntree(IssueSuppression.ECHEC, "x", "rm: /system/x: Read-only file system"), issue)
        assertEquals("rm -f '/system/x'", tv.commandes.single())
    }

    @Test
    fun `l'explorateur copie un fichier aussitot, un dossier apres confirmation`() = runTest {
        val tv = Televiseur(
            contenus = mapOf("/sdcard/a.mkv" to "a", "/sdcard/Films/bande|annonce.mp4" to "b", "/sdcard/Films/Séries/Saison 1/e01.mkv" to "e"),
            inventaires = mapOf("/sdcard" to ResultatShell(0, ""), "/sdcard/Films" to inventaireFilms),
        )
        val signaux = mutableListOf<SignalFichiers>()
        val explorateur = ExplorateurFichiers(NavigateurFichiers(tv, tv, tv), this, signaux::add)
        val disque = Disque()

        explorateur.rapatrier(EntreeDistante("a.mkv", NatureEntree.FICHIER, 1, 0L), disque, "a.mkv")
        advanceUntilIdle()
        assertEquals(listOf("a.mkv"), disque.fichiers.keys.toList())
        assertEquals(SensTransfert.RECEPTION, (signaux.single() as SignalFichiers.Depot).resultat.sens)

        explorateur.rapatrier(films, disque, "Films")
        advanceUntilIdle()
        assertNotNull(explorateur.etat.value.rapatriement)
        assertTrue(explorateur.etat.value.occupe)
        assertEquals("Rien ne se copie avant la confirmation", 1, disque.fichiers.size)

        explorateur.confirmerRapatriement()
        advanceUntilIdle()
        assertEquals(3, disque.fichiers.size)
        assertNull(explorateur.etat.value.avancee)
        assertFalse(explorateur.etat.value.occupe)
        assertEquals(2, (signaux.last() as SignalFichiers.Depot).resultat.envoyes)
    }

    @Test
    fun `l'explorateur n'efface qu'apres confirmation, puis relit le dossier`() = runTest {
        val tv = Televiseur(inventaires = mapOf("/sdcard/Films" to inventaireFilms))
        val signaux = mutableListOf<SignalFichiers>()
        val explorateur = ExplorateurFichiers(NavigateurFichiers(tv, tv), this, signaux::add)

        explorateur.demanderSuppression(films)
        advanceUntilIdle()
        assertEquals(2, explorateur.etat.value.suppression?.fichiers)
        assertTrue(tv.commandes.none { it.contains("rm -") })

        explorateur.demanderSuppression(films)
        assertEquals(SignalFichiers.Occupe, signaux.single())

        explorateur.confirmerSuppression()
        advanceUntilIdle()
        assertTrue(tv.commandes.last { !it.startsWith("[ -d") }.endsWith("rm -rf '/sdcard/Films'"))
        assertEquals(SignalFichiers.Suppression(SuppressionEntree(IssueSuppression.SUPPRIME, "Films")), signaux.last())
        assertTrue("Le dossier est relu", tv.commandes.last().startsWith("[ -d '/sdcard' ]"))
        assertFalse(explorateur.etat.value.occupe)
    }

    @Test
    fun `une suppression refusee d'emblee ne demande rien`() = runTest {
        val tv = Televiseur(inventaires = mapOf("/sdcard/Android" to ResultatShell(5, "")))
        val signaux = mutableListOf<SignalFichiers>()
        val explorateur = ExplorateurFichiers(NavigateurFichiers(tv, tv), this, signaux::add)

        explorateur.demanderSuppression(EntreeDistante("Android", NatureEntree.DOSSIER, 4096, 0L))
        advanceUntilIdle()

        assertNull(explorateur.etat.value.suppression)
        assertEquals(SignalFichiers.Suppression(SuppressionEntree(IssueSuppression.PROTEGE, "Android")), signaux.single())
    }
}
