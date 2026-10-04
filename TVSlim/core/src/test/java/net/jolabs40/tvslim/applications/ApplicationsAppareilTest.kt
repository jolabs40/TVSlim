package net.jolabs40.tvslim.applications

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.EnvoyeurFichiers
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Base64

/**
 * L'onglet Applications contre un appareil simulé : ce que l'aide rend, ce qu'on en garde, et ce qu'on refuse. La vraie
 * aide a été éprouvée sur la TCL et le Pixel (46 et 227 applications), puis par `ApplicationsMaterielTest`.
 */
class ApplicationsAppareilTest {

    private val icone = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val enBase64 = Base64.getEncoder().encodeToString(icone)

    private val liste = """
        TVSLIM_AIDE 1
        A	com.google.android.youtube.tv	1234	1	1	com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity
        A	com.spocky.projengmenu	95	0	1	com.spocky.projengmenu/com.spocky.projengmenu.ui.home.MainActivity
        A	net.jolabs40.tvslim	1	0	0	-
        WARNING: linker: something noisy
    """.trimIndent()

    /** Un appareil qui répond à l'aide, et note ce qu'on lui demande. */
    private inner class Appareil(
        val reponseListe: String = liste,
        val tiers: Set<String> = setOf("com.spocky.projengmenu", "net.jolabs40.tvslim"),
    ) : ExecuteurCommande, EnvoyeurFichiers {
        val commandes = mutableListOf<String>()
        var envoye: ByteArray? = null

        override suspend fun executer(commande: String): ResultatShell {
            commandes += commande
            return when {
                commande.endsWith(" liste") -> ResultatShell(0, reponseListe)
                commande.contains(" details ") -> ResultatShell(
                    0,
                    "TVSLIM_AIDE 1\n" + commande.substringAfter(" details ").split(' ').drop(1).joinToString("\n") { paquet ->
                        if (paquet == "net.jolabs40.tvslim") "E\t$paquet\tNameNotFoundException" else "D\t$paquet\tNom de $paquet\t$enBase64"
                    },
                )
                commande.startsWith("pm list packages -3 ") ->
                    ResultatShell(0, tiers.filter { it.contains(commande.substringAfterLast(' ')) }.joinToString("\n") { "package:$it" })
                commande.startsWith("pm uninstall ") -> ResultatShell(0, "Success")
                commande.startsWith("am start ") -> ResultatShell(0, "Starting: Intent { cmp=… }")
                else -> ResultatShell(0, "")
            }
        }

        override suspend fun envoyer(
            source: InputStream,
            taille: Long,
            chemin: String,
            date: Long,
            annule: () -> Boolean,
            surEnvoi: (envoye: Long) -> Unit,
        ): ResultatShell {
            envoye = source.use { it.readBytes() }
            return ResultatShell(0, "")
        }
    }

    private class CacheMemoire : CacheApplications {
        val contenu = mutableMapOf<String, DetailsApplication>()
        override fun lire(paquet: String, versionCode: Long) = contenu["$paquet@$versionCode"]
        override fun ecrire(paquet: String, versionCode: Long, details: DetailsApplication) {
            contenu["$paquet@$versionCode"] = details
        }
    }

    private fun aide(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))

    @Test
    fun `la liste puis les details, et l'aide effacee a la fin`() = runTest {
        val appareil = Appareil()
        val etapes = mutableListOf<Pair<Int, Int>>()

        val resultat = LecteurApplications(appareil, appareil, ::aide, CacheMemoire())
            .lire { _, fait, total -> etapes += fait to total } as ResultatLecture.Lues

        assertTrue(appareil.envoye!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf(0 to 3, 3 to 3), etapes)
        val youtube = resultat.applications.single { it.paquet == "com.google.android.youtube.tv" }
        assertEquals("Nom de com.google.android.youtube.tv", youtube.nom)
        assertTrue(youtube.icone!!.contentEquals(icone))
        assertTrue(youtube.systeme)
        // Un paquet que l'aide n'a pas su lire garde son nom de paquet, sans icône.
        val tvslim = resultat.applications.single { it.paquet == "net.jolabs40.tvslim" }
        assertEquals("net.jolabs40.tvslim", tvslim.nom)
        assertNull(tvslim.icone)
        assertNull(tvslim.lancement)
        assertFalse(tvslim.active)
        assertEquals("rm -f ${LecteurApplications.CHEMIN_AIDE}", appareil.commandes.last())
    }

    @Test
    fun `ce que le cache connait ne se relit pas`() = runTest {
        val cache = CacheMemoire()
        cache.ecrire("com.google.android.youtube.tv", 1234, DetailsApplication("YouTube", icone))
        cache.ecrire("com.spocky.projengmenu", 95, DetailsApplication("Projectivy", icone))
        val appareil = Appareil()

        val resultat = LecteurApplications(appareil, appareil, ::aide, cache).lire() as ResultatLecture.Lues

        val details = appareil.commandes.single { it.contains(" details ") }
        assertTrue(details, details.endsWith(" details ${LecteurApplications.TAILLE_ICONE} net.jolabs40.tvslim"))
        assertEquals(listOf("net.jolabs40.tvslim", "Projectivy", "YouTube"), resultat.applications.map { it.nom })
    }

    @Test
    fun `une aide d'une autre version est refusee`() = runTest {
        val appareil = Appareil(reponseListe = "TVSLIM_AIDE 2\nA\tx\t1\t0\t1\t-")

        val resultat = LecteurApplications(appareil, appareil, ::aide, CacheMemoire()).lire()

        assertEquals(CauseLecture.AIDE_REFUSEE, (resultat as ResultatLecture.Echec).cause)
        assertEquals("rm -f ${LecteurApplications.CHEMIN_AIDE}", appareil.commandes.last())
    }

    @Test
    fun `sans l'aide dans les ressources, rien ne part`() = runTest {
        val appareil = Appareil()

        val resultat = LecteurApplications(appareil, appareil, { null }, CacheMemoire()).lire()

        assertEquals(CauseLecture.AIDE_ABSENTE, (resultat as ResultatLecture.Echec).cause)
        assertTrue(appareil.commandes.isEmpty())
    }

    @Test
    fun `seule une application installee se desinstalle, et le journal le garde`() = runTest {
        val appareil = Appareil()
        val journal = JournalRepository(Files.createTempFile("journal", ".json").toFile().apply { delete() })
        val actions = ActionsApplications(appareil) { journal }
        val lecture = LecteurApplications(appareil, appareil, ::aide, CacheMemoire()).lire() as ResultatLecture.Lues
        val projectivy = lecture.applications.single { it.paquet == "com.spocky.projengmenu" }
        val youtube = lecture.applications.single { it.paquet == "com.google.android.youtube.tv" }

        assertFalse(actions.desinstaller(youtube).reussi)
        assertFalse(appareil.commandes.any { it == "pm uninstall com.google.android.youtube.tv" })

        assertTrue(actions.desinstaller(projectivy).reussi)
        val consigne = journal.actions.value.single()
        assertEquals(TypeAction.DESINSTALLATION, consigne.type)
        assertEquals("", consigne.commandeAnnulation)
    }

    @Test
    fun `ouvrir passe par l'activite du menu, entre apostrophes`() = runTest {
        val appareil = Appareil()
        val actions = ActionsApplications(appareil) { null }
        val youtube = LecteurApplications.lireListe(liste).single { it.paquet == "com.google.android.youtube.tv" }

        assertTrue(actions.ouvrir(youtube).reussi)
        assertEquals("am start -n '${youtube.lancement}'", appareil.commandes.last())
        assertFalse(actions.ouvrir(LecteurApplications.lireListe(liste).single { it.lancement == null }).reussi)
    }

    @Test
    fun `le cache sur le disque relit noms et icones`() {
        val dossier = Files.createTempDirectory("cache-applications").toFile()
        try {
            CacheApplicationsFichiers(dossier).ecrire("com.a", 3, DetailsApplication("A", icone))

            val relu = CacheApplicationsFichiers(dossier).lire("com.a", 3)

            assertEquals("A", relu!!.nom)
            assertTrue(relu.icone.contentEquals(icone))
            assertNull(CacheApplicationsFichiers(dossier).lire("com.a", 4))
        } finally {
            dossier.deleteRecursively()
        }
    }

    @Test
    fun `l'aide embarquee est dans les ressources du noyau`() {
        val aide = File("src/main/assets/${LecteurApplications.CHEMIN_RESSOURCE}")
        assertTrue("${aide.absolutePath} : lancer ./gradlew :aide:copierDansLeNoyau", aide.isFile)
        // Un APK : une archive zip, avec son code.
        val octets = aide.readBytes()
        assertEquals('P'.code.toByte(), octets[0])
        assertEquals('K'.code.toByte(), octets[1])
        assertTrue(String(octets, Charsets.ISO_8859_1).contains("classes.dex"))
    }
}
