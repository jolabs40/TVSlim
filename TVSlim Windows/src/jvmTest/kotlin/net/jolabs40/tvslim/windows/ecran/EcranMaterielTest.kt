package net.jolabs40.tvslim.windows.ecran

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.ecran.Arret
import net.jolabs40.tvslim.ecran.CaptureEcran
import net.jolabs40.tvslim.ecran.Demarrage
import net.jolabs40.tvslim.ecran.EnregistrementTv
import net.jolabs40.tvslim.ecran.ResultatCapture
import net.jolabs40.tvslim.windows.Emplacements
import net.jolabs40.tvslim.windows.adb.ClientAdb
import net.jolabs40.tvslim.windows.adb.DepotCles
import net.jolabs40.tvslim.windows.maj.ClientGithub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Screen of a real TV. Screenshot and video use the app's key, as in TV Slim: the video records for six seconds on
 * the TV, is copied, then deleted, leaving nothing behind. The scrcpy test downloads the pinned release from GitHub,
 * opens the mirror, then closes its window the way a user would.
 *
 *     ./gradlew jvmTest --tests "*EcranMaterielTest*" '-Pmateriel=192.168.2.135' --rerun
 *     ./gradlew jvmTest --tests "*EcranMaterielTest*" '-Pmateriel=192.168.2.135' -Pscrcpy=1 --rerun
 *
 * The second one opens a scrcpy window on the desktop during the test.
 */
class EcranMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")
    private val sortie = File(System.getProperty("tvslim.captures") ?: "build/captures")

    @Test
    fun `the screenshot returns a PNG of the TV screen`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> pour essayer sur un vrai téléviseur", hote != null)
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        assertTrue("Connexion à $hote : ${client.connexion.value}", client.connecter(hote!!))
        try {
            val debut = System.currentTimeMillis()
            val resultat = CaptureEcran(client).capturer()
            println("Capture en ${System.currentTimeMillis() - debut} ms")
            assertTrue(resultat.toString(), resultat is ResultatCapture.Reussie)
            resultat as ResultatCapture.Reussie
            println("${resultat.largeur} × ${resultat.hauteur}, ${resultat.png.size} octets")
            sortie.mkdirs()
            File(sortie, "ecran-materiel.png").writeBytes(resultat.png)

            // The session still works after a binary read.
            assertEquals(0, client.executer("echo apres").code)
        } finally {
            client.deconnecter()
        }
    }

    @Test
    fun `the video records on the TV, is copied, then deleted`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> pour essayer sur un vrai téléviseur", hote != null)
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        assertTrue("Connexion à $hote : ${client.connexion.value}", client.connecter(hote!!))
        try {
            val enregistrement = EnregistrementTv(client, client)
            val demarrage = enregistrement.demarrer()
            println("Démarrage : $demarrage")
            assertTrue(demarrage.toString(), demarrage is Demarrage.Lance)

            // The TV Slim session stays free while recording: the recorder is detached.
            Thread.sleep(3_000)
            assertEquals(0, client.executer("echo pendant").code)
            assertEquals(true, enregistrement.vivant())
            Thread.sleep(3_000)

            val arret = enregistrement.arreter()
            println("Arrêt : $arret")
            assertTrue(arret.toString(), arret is Arret.Termine)
            val taille = (arret as Arret.Termine).taille

            sortie.mkdirs()
            val video = File(sortie, "ecran-materiel.mp4").apply { delete() }
            val debut = System.currentTimeMillis()
            val copie = video.outputStream().use { enregistrement.rapatrier(it, taille) }
            println("Copie de $taille octets en ${System.currentTimeMillis() - debut} ms : $copie")
            assertTrue(copie.toString(), copie.reussi)
            assertEquals(taille, video.length())
            // SIGINT lets the recorder write the "moov" index, so the video plays to the end.
            assertTrue("vidéo sans index", String(video.readBytes(), Charsets.ISO_8859_1).contains("moov"))

            enregistrement.nettoyer()
            assertEquals("", client.executer("ls ${EnregistrementTv.VIDEO} ${EnregistrementTv.PID} 2>/dev/null").sortie)
        } finally {
            client.deconnecter()
        }
    }

    @Test
    fun `scrcpy downloads, opens the mirror, and closes cleanly`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> -Pscrcpy=1", hote != null && System.getProperty("tvslim.scrcpy") != null)
        val dossier = Files.createTempDirectory("tvslim-scrcpy").toFile()
        try {
            // The actual release file; its checksum is verified on the way.
            val exe = InstallationScrcpy(ClientGithub("Genymobile/scrcpy", "essai"), dossier).installer { }
            // The downloaded copy wins over any other, and its version is recent enough.
            assertEquals(exe.canonicalFile, LocalisationScrcpy(dossier).trouver()?.canonicalFile)

            val session = SessionScrcpy.lancer(exe, ArgumentsScrcpy.miroir(hote!!, 5555, "TV Slim - essai"))
            Thread.sleep(6_000)
            assertTrue("scrcpy s'est arrêté seul : ${session.sortie}", session.vivante)
            session.arreter()
            val code = session.attendre()
            println("scrcpy : code $code\n" + session.sortie.joinToString("\n"))
            assertEquals(session.sortie.joinToString("\n"), 0, code)
        } finally {
            dossier.deleteRecursively()
        }
    }
}
