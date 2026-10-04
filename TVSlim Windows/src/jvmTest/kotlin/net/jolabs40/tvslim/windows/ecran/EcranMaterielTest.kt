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
 * L'écran d'un vrai téléviseur. La capture et la vidéo passent par la clé de l'application, comme dans TV Slim : la
 * vidéo s'enregistre six secondes sur le téléviseur, se copie, puis s'efface — rien n'y reste. L'essai de scrcpy
 * télécharge la version épinglée depuis GitHub, ouvre le miroir, puis ferme sa fenêtre comme on le ferait à la
 * souris.
 *
 *     ./gradlew jvmTest --tests "*EcranMaterielTest*" '-Pmateriel=192.168.2.135' --rerun
 *     ./gradlew jvmTest --tests "*EcranMaterielTest*" '-Pmateriel=192.168.2.135' -Pscrcpy=1 --rerun
 *
 * Le second ouvre une fenêtre scrcpy sur le bureau pendant l'essai.
 */
class EcranMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")
    private val sortie = File(System.getProperty("tvslim.captures") ?: "build/captures")

    @Test
    fun `la capture rend un PNG de l'ecran du televiseur`() = runBlocking<Unit> {
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

            // La session sert encore après une lecture binaire.
            assertEquals(0, client.executer("echo apres").code)
        } finally {
            client.deconnecter()
        }
    }

    @Test
    fun `la video s'enregistre sur le televiseur, se copie, puis s'efface`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> pour essayer sur un vrai téléviseur", hote != null)
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        assertTrue("Connexion à $hote : ${client.connexion.value}", client.connecter(hote!!))
        try {
            val enregistrement = EnregistrementTv(client, client)
            val demarrage = enregistrement.demarrer()
            println("Démarrage : $demarrage")
            assertTrue(demarrage.toString(), demarrage is Demarrage.Lance)

            // La session de TV Slim reste libre pendant l'enregistrement : l'enregistreur est détaché.
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
            // SIGINT a laissé l'enregistreur écrire l'index « moov » : la vidéo se lit jusqu'au bout.
            assertTrue("vidéo sans index", String(video.readBytes(), Charsets.ISO_8859_1).contains("moov"))

            enregistrement.nettoyer()
            assertEquals("", client.executer("ls ${EnregistrementTv.VIDEO} ${EnregistrementTv.PID} 2>/dev/null").sortie)
        } finally {
            client.deconnecter()
        }
    }

    @Test
    fun `scrcpy se telecharge, ouvre le miroir, et se ferme proprement`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> -Pscrcpy=1", hote != null && System.getProperty("tvslim.scrcpy") != null)
        val dossier = Files.createTempDirectory("tvslim-scrcpy").toFile()
        try {
            // Le vrai fichier de la publication : son empreinte est vérifiée en chemin.
            val exe = InstallationScrcpy(ClientGithub("Genymobile/scrcpy", "essai"), dossier).installer { }
            // La copie téléchargée passe avant toute autre, et sa version suffit.
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
