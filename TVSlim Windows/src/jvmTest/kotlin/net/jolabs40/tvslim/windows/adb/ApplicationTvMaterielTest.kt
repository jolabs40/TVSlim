package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.applicationtv.ApplicationTv
import net.jolabs40.tvslim.applicationtv.EtatApplicationTv
import net.jolabs40.tvslim.applicationtv.ResultatTv
import net.jolabs40.tvslim.applicationtv.SourceGithub
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.installation.InstallationApk
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.windows.Emplacements
import net.jolabs40.tvslim.windows.InfosApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Updates the TV app from GitHub on a real TV: latest `android-v*` release, certificate check, install, grant,
 * watchdog. The foreground app must stay there (an app already running is not relaunched). Installs on the TV,
 * with a temporary journal:
 *
 *     ./gradlew jvmTest --tests "*ApplicationTvMaterielTest*" '-Pmateriel=192.168.2.135' -PapplicationTv=1 --rerun
 */
class ApplicationTvMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")
    private val permis = System.getProperty("tvslim.applicationtv") == "1"

    @Test
    fun `the TV app updates from GitHub without bringing anything to the foreground`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> -PapplicationTv=1 : installe sur un vrai téléviseur", hote != null && permis)
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        assertTrue("Connexion à $hote : ${client.connexion.value}", client.connecter(hote!!))
        val dossier = Files.createTempDirectory("tvslim-application-tv").toFile()
        try {
            val journal = JournalRepository(Files.createTempFile("journal", ".json").toFile().also { it.delete() })
            val application = ApplicationTv(
                executeur = client,
                installation = InstallationApk(client, client, journal),
                moteur = MoteurDebloat(client, journal),
                lecteur = LecteurDistant(client),
                source = SourceGithub(agent = "TVSlim-Windows/essai"),
                empreinteAttendue = InfosApp.EMPREINTE_CERTIFICAT_ANDROID,
                dossier = dossier,
            )
            suspend fun premierPlan() = client.executer("dumpsys activity activities | grep -m1 topResumedActivity").sortie.trim()

            val disponible = application.derniere()
            val avant = application.situation(disponible)
            val ecranAvant = premierPlan()
            println("Avant : ${avant.installee?.versionName} installée, ${disponible?.version} publiée, ${avant.etat} ; au premier plan : $ecranAvant")
            println("État : ${client.executer(ApplicationTv.COMMANDE_ETAT).sortie.trim()}")

            val etapes = mutableListOf<String>()
            val resultat = application.installer { etapes += it.javaClass.simpleName }
            val apres = application.situation(disponible)
            val ecranApres = premierPlan()
            println("Résultat : $resultat")
            println("Étapes : ${etapes.distinct()}")
            println("Après : ${apres.installee?.versionName}, ${apres.etat} ; au premier plan : $ecranApres")
            println("Journal : ${journal.actions.value.map { "${it.type} ${it.cible}" }}")

            assertTrue(resultat.toString(), resultat is ResultatTv.Reussi && resultat.autorisee && resultat.gardien)
            assertEquals(EtatApplicationTv.A_JOUR, apres.etat)
            assertEquals("Rien ne doit avoir pris le premier plan", ecranAvant, ecranApres)
        } finally {
            dossier.deleteRecursively()
            client.deconnecter()
        }
    }
}
