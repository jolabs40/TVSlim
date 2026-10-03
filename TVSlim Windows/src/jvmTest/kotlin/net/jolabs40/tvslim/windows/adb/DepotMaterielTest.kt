package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.fichiers.CheminDistant
import net.jolabs40.tvslim.fichiers.ExamenDepot
import net.jolabs40.tvslim.fichiers.IssueCreation
import net.jolabs40.tvslim.fichiers.LectureDossier
import net.jolabs40.tvslim.fichiers.NatureRaccourci
import net.jolabs40.tvslim.fichiers.NavigateurFichiers
import net.jolabs40.tvslim.fichiers.citer
import net.jolabs40.tvslim.windows.Emplacements
import net.jolabs40.tvslim.windows.fichiers.lotDepuis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Parcourir et déposer sur un vrai téléviseur, par le client ADB de l'application et le noyau partagé. Ne
 * tourne que sur demande, parce qu'il **écrit** — dans un dossier `tvslim-essai-…` de Téléchargements, effacé
 * à la fin :
 *
 *     ./gradlew jvmTest --tests "*DepotMaterielTest*" '-Pmateriel=192.168.2.135' -Pdepot=1 --rerun
 */
class DepotMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")

    @Test
    fun `un dossier part entier, intact, et le reste se dit`() = runBlocking<Unit> {
        assumeTrue(
            "-Pmateriel=<adresse> -Pdepot=1 pour écrire sur un vrai téléviseur",
            hote != null && System.getProperty("tvslim.depot") != null,
        )
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        assertTrue("Connexion à $hote : ${client.connexion.value}", client.connecter(hote!!))
        val navigateur = NavigateurFichiers(client, client)
        val nom = "tvslim-essai-${System.currentTimeMillis()}"
        val destination = "/sdcard/Download"
        val essai = CheminDistant.joindre(destination, nom)

        // Sur place : un texte, un nom à apostrophe et espaces, 5 Mo aléatoires dans un sous-dossier, un dossier vide.
        val local = File(Files.createTempDirectory("tvslim-depot").toFile(), nom).apply { mkdirs() }
        File(local, "a.txt").writeText("bonjour")
        File(local, "l'été 2024.txt").writeText("apostrophe")
        val gros = File(local, "sous/b.bin").apply { parentFile.mkdirs(); writeBytes(Random(7).nextBytes(5_000_000)) }
        File(local, "vide").mkdirs()

        try {
            val raccourcis = navigateur.raccourcis()
            println("Raccourcis : ${raccourcis.map { it.chemin }}")
            assertEquals(NatureRaccourci.INTERNE, raccourcis.first().nature)
            assertTrue(navigateur.lister("/data") is LectureDossier.Refusee)
            assertTrue(navigateur.lister("/nexiste/pas") is LectureDossier.Introuvable)
            val racine = navigateur.lister("/") as LectureDossier.Lue
            assertTrue("sdcard est un lien vers un dossier", racine.entrees.single { it.nom == "sdcard" }.let { it.lien && it.dossier })

            val examen = navigateur.examiner(lotDepuis(listOf(local)), destination)
            val plan = (examen as ExamenDepot.Pret).plan
            assertTrue(plan.existants.isEmpty())

            var signes = 0
            val debut = System.currentTimeMillis()
            val resultat = navigateur.deposer(plan) { signes++ }
            val duree = System.currentTimeMillis() - debut
            println("Envoi en $duree ms, $signes signes : $resultat")
            assertTrue(resultat.toString(), resultat.complet)

            val lu = navigateur.lister(essai) as LectureDossier.Lue
            assertEquals(listOf("sous", "vide", "a.txt", "l'été 2024.txt"), lu.entrees.map { it.nom })
            val distant = navigateur.lister("$essai/sous") as LectureDossier.Lue
            assertEquals(5_000_000L, distant.entrees.single().taille)
            val md5 = client.executer("md5sum ${citer("$essai/sous/b.bin")}").sortie.substringBefore(' ')
            assertEquals(empreinte(gros), md5)

            // Une seconde fois : le dossier est déjà là, et le dire ne change rien à l'envoi.
            assertEquals(listOf(nom), (navigateur.examiner(lotDepuis(listOf(local)), destination) as ExamenDepot.Pret).plan.existants)

            // Annuler au milieu d'un gros fichier : l'envoi s'arrête, la session sert encore.
            val enorme = File(local.parentFile, "enorme.bin").apply { writeBytes(Random(3).nextBytes(60_000_000)) }
            var annule = false
            val plan2 = (navigateur.examiner(lotDepuis(listOf(enorme)), essai) as ExamenDepot.Pret).plan
            val arrete = navigateur.deposer(plan2, annule = { annule }) { if (it.envoye > 5_000_000) annule = true }
            println("Annulé : $arrete")
            assertTrue(arrete.annule)
            val apres = navigateur.lister(essai) as LectureDossier.Lue
            println("Après l'annulation : ${apres.entrees.map { "${it.nom} ${it.taille}" }}")

            // Là où le shell n'écrit pas, le refus du téléviseur arrive tel quel, fichier par fichier.
            val refuse = navigateur.deposer(plan.copy(destination = "/system", lot = lotDepuis(listOf(File(local, "a.txt")))))
            println("Vers /system : $refuse")
            assertEquals(0, refuse.envoyes)
            assertFalse(refuse.interrompu)
            assertTrue(refuse.echecs.isNotEmpty())

            assertEquals(IssueCreation.CREE, navigateur.creerDossier(essai, "Nouveau dossier").issue)
            assertEquals(IssueCreation.EXISTE, navigateur.creerDossier(essai, "Nouveau dossier").issue)
        } finally {
            println("Nettoyage : ${client.executer("rm -r ${citer(essai)}")}")
            client.deconnecter()
            local.parentFile.deleteRecursively()
        }
    }

    private fun empreinte(fichier: File): String =
        MessageDigest.getInstance("MD5").digest(fichier.readBytes()).joinToString("") { "%02x".format(it) }
}
