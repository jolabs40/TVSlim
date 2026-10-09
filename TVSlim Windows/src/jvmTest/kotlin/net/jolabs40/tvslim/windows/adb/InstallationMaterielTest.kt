package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.installation.ExamenApk
import net.jolabs40.tvslim.installation.InstallationApk
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.windows.Emplacements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Sends a real APK to a real TV, through the app's ADB client and the shared core. Opt-in because it installs:
 *
 *     ./gradlew jvmTest --tests "*InstallationMaterielTest*" '-Pmateriel=192.168.2.135' '-Papk=C:/…/app-tv-debug.apk' --rerun
 *
 * Pick a harmless APK: this repo's TV app, already installed and signed with the same key, is reinstalled and
 * keeps its data and its `WRITE_SECURE_SETTINGS` permission. Temporary journal; uses the app's ADB key, like
 * `CapturesMaterielTest`.
 */
class InstallationMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")
    private val chemin: String? = System.getProperty("tvslim.apk")

    @Test
    fun `an APK is sent, installed and logged`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> -Papk=<fichier> pour installer sur un vrai téléviseur", hote != null && chemin != null)
        val apk = File(chemin!!)
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        val connecte = client.connecter(hote!!)
        assertTrue("Connexion à $hote : ${client.connexion.value}", connecte)
        try {
            val journal = JournalRepository(File(Files.createTempDirectory("tvslim-installation").toFile(), "journal.json"))
            val installation = InstallationApk(client, client, journal)

            val examen = installation.examiner(apk, apk.name)
            println("Examen : $examen")
            assertTrue(examen.toString(), examen is ExamenApk.Pret)

            val avancement = mutableListOf<Long>()
            val debut = System.currentTimeMillis()
            val resultat = installation.installer((examen as ExamenApk.Pret).apk) { envoye, _ -> avancement += envoye }
            println("Résultat en ${System.currentTimeMillis() - debut} ms, ${avancement.size} signes d'avancement : $resultat")

            assertTrue(resultat.toString(), resultat is ResultatInstallation.Reussie)
            assertEquals(apk.length(), avancement.last())
            assertEquals(TypeAction.INSTALLATION, journal.actions.value.single().type)
        } finally {
            client.deconnecter()
        }
    }
}
