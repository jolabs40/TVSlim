package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.fichiers.EntreeDistante
import net.jolabs40.tvslim.fichiers.ExamenDepot
import net.jolabs40.tvslim.fichiers.ExamenRapatriement
import net.jolabs40.tvslim.fichiers.ExamenSuppression
import net.jolabs40.tvslim.fichiers.InventaireDossier
import net.jolabs40.tvslim.fichiers.IssueSuppression
import net.jolabs40.tvslim.fichiers.LectureDossier
import net.jolabs40.tvslim.fichiers.NatureEntree
import net.jolabs40.tvslim.fichiers.NatureSuppression
import net.jolabs40.tvslim.fichiers.NavigateurFichiers
import net.jolabs40.tvslim.fichiers.PlanRapatriement
import net.jolabs40.tvslim.fichiers.PlanSuppression
import net.jolabs40.tvslim.fichiers.citer
import net.jolabs40.tvslim.windows.Emplacements
import net.jolabs40.tvslim.windows.fichiers.CibleDisque
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
 * Copy to PC and delete on a real TV, through the app's ADB client and the shared core. Opt-in because it writes
 * and deletes, only in a `tvslim-copie-...` folder under Download and in `/data/local/tmp`:
 *
 *     ./gradlew jvmTest --tests "*CopieMaterielTest*" '-Pmateriel=192.168.2.135' -Pdepot=1 --rerun
 *
 * The whole-storage guard is only exercised read-only, or with `echo` in place of `rm`: a bug would wipe the TV's
 * storage.
 */
class CopieMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")

    @Test
    fun `copy to PC, stop, delete, and the guard holds`() = runBlocking<Unit> {
        assumeTrue(
            "-Pmateriel=<adresse> -Pdepot=1 pour écrire et effacer sur un vrai téléviseur",
            hote != null && System.getProperty("tvslim.depot") != null,
        )
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        assertTrue("Connexion à $hote : ${client.connexion.value}", client.connecter(hote!!))
        val navigateur = NavigateurFichiers(client, client, client)
        val nom = "tvslim-copie-${System.currentTimeMillis()}"
        val telechargements = "/sdcard/Download"
        val essai = "$telechargements/$nom"
        val temporaire = "/data/local/tmp/$nom"

        val local = Files.createTempDirectory("tvslim-copie").toFile()
        val source = File(local, "source/$nom").apply { mkdirs() }
        File(source, "a.txt").writeText("bonjour")
        val gros = File(source, "sous/b.bin").apply { parentFile.mkdirs(); writeBytes(Random(7).nextBytes(5_000_000)) }
        File(source, "vide").mkdirs()
        val enorme = File(local, "enorme.bin").apply { writeBytes(Random(3).nextBytes(60_000_000)) }
        val pc = File(local, "pc").apply { mkdirs() }

        try {
            // Seeded through the upload path, tested on its own.
            val depot = navigateur.examiner(lotDepuis(listOf(source)), telechargements) as ExamenDepot.Pret
            assertTrue(navigateur.deposer(depot.plan).complet)
            val planEnorme = navigateur.examiner(lotDepuis(listOf(enorme)), essai) as ExamenDepot.Pret
            assertTrue(navigateur.deposer(planEnorme.plan).complet)

            // 1. The whole folder, including a subfolder and an empty folder, under names Windows accepts.
            val dossier = entree(navigateur, telechargements, nom)
            val plan = pret(navigateur.preparerRapatriement(telechargements, dossier, CibleDisque(pc), nom))
            println("Plan : ${plan.fichiers.size} fichiers, ${plan.taille} octets, dossiers ${plan.dossiers}")
            assertEquals(3, plan.fichiers.size)
            assertTrue(plan.dossiers.containsAll(listOf(nom, "$nom/sous", "$nom/vide")))
            assertFalse(plan.existant)

            val debut = System.currentTimeMillis()
            val copie = navigateur.rapatrier(plan)
            println("Copie en ${System.currentTimeMillis() - debut} ms : $copie")
            assertTrue(copie.toString(), copie.complet)
            val arrive = File(pc, nom)
            assertEquals("bonjour", File(arrive, "a.txt").readText())
            assertEquals(empreinte(gros), empreinte(File(arrive, "sous/b.bin")))
            assertEquals(empreinte(enorme), empreinte(File(arrive, "enorme.bin")))
            assertTrue(File(arrive, "vide").isDirectory)
            val dateTv = (navigateur.lister(essai) as LectureDossier.Lue).entrees.single { it.nom == "a.txt" }.date
            assertEquals("La date du téléviseur, à la seconde", dateTv / 1000, File(arrive, "a.txt").lastModified() / 1000)
            assertTrue("Aucun fichier provisoire", arrive.walk().none { it.name.endsWith(CibleDisque.SUFFIXE_PROVISOIRE) })

            // 1b. Names Android allows and Windows rejects. Shared storage rejects them too ("Operation not
            // permitted" for a `:`), so they are created in /data/local/tmp.
            val noms = "$temporaire-noms"
            val creation = client.executer(
                "mkdir ${citer(noms)} && printf apostrophe > ${citer("$noms/l'été 12:30.txt")} && printf nul > ${citer("$noms/NUL.txt")}",
            )
            assertTrue(creation.toString(), creation.reussi)
            val dossierNoms = entree(navigateur, "/data/local/tmp", "$nom-noms")
            val planNoms = pret(navigateur.preparerRapatriement("/data/local/tmp", dossierNoms, CibleDisque(pc), "noms"))
            assertTrue(navigateur.rapatrier(planNoms).complet)
            assertEquals("apostrophe", File(pc, "noms/l'été 12_30.txt").readText())
            assertEquals("nul", File(pc, "noms/_NUL.txt").readText())

            // 2. Stop halfway through a large file: nothing lands, the previous copy stays, the session still works.
            val grosDistant = entree(navigateur, essai, "enorme.bin")
            val planGros = pret(navigateur.preparerRapatriement(essai, grosDistant, CibleDisque(arrive), "enorme.bin"))
            var annule = false
            val arrete = navigateur.rapatrier(planGros, annule = { annule }) { if (it.envoye > 5_000_000) annule = true }
            println("Arrêtée : $arrete")
            assertTrue(arrete.annule)
            assertEquals("Le fichier précédent est intact", empreinte(enorme), empreinte(File(arrive, "enorme.bin")))
            assertTrue(arrive.walk().none { it.name.endsWith(CibleDisque.SUFFIXE_PROVISOIRE) })
            assertEquals(EtatConnexion.CONNECTE, client.connexion.value.etat)
            assertEquals("encore", client.executer("echo encore").sortie)

            // 3. A missing file: the TV's error comes back as is, and the session survives.
            val fantome = EntreeDistante("fantome.bin", NatureEntree.FICHIER, 1, 0L)
            val absent = navigateur.rapatrier(pret(navigateur.preparerRapatriement(essai, fantome, CibleDisque(pc), "f.bin")))
            println("Fichier absent : ${absent.echecs}")
            assertEquals(1, absent.echecs.size)
            assertFalse(absent.interrompu)
            assertFalse(File(pc, "f.bin").exists())

            // 4. The guard: never a whole storage, under any of its names.
            for ((parent, racine) in listOf(
                "/storage" to "emulated",
                "/storage/emulated" to "0",
                "/storage/self" to "primary",
                "/sdcard" to "Android",
                "/" to "storage",
                "/" to "sdcard",
                "/data/local" to "tmp",
            )) {
                val examen = navigateur.preparerSuppression(parent, EntreeDistante(racine, NatureEntree.DOSSIER, 0, 0L))
                assertEquals("$parent/$racine", ExamenSuppression.Protege, examen)
            }
            // The delete command carries the same guard, exercised with echo in place of rm.
            val essaiGarde = InventaireDossier.commandeSuppression("/storage/emulated/0", NatureSuppression.DOSSIER)
                .replace("; rm -rf ", "; echo PASSE ")
            val garde = client.executer(essaiGarde)
            assertEquals("Garde : $garde", 5, garde.code)
            assertFalse(garde.sortie.contains("PASSE"))
            // An ordinary folder passes, with its contents.
            val ordinaire = navigateur.preparerSuppression(telechargements, dossier) as ExamenSuppression.Pret
            println("Suppression du dossier d'essai : ${ordinaire.plan}")
            assertEquals(PlanSuppression(essai, NatureSuppression.DOSSIER, 3, 2, ordinaire.plan.taille), ordinaire.plan)

            // 5. A file is deleted; a symlink is deleted without its target.
            val fichier = navigateur.preparerSuppression(essai, entree(navigateur, essai, "a.txt")) as ExamenSuppression.Pret
            assertEquals(IssueSuppression.SUPPRIME, navigateur.supprimer(fichier.plan).issue)
            assertTrue((navigateur.lister(essai) as LectureDossier.Lue).entrees.none { it.nom == "a.txt" })

            val cible = "$temporaire-cible"
            val lien = "$temporaire-lien"
            client.executer("mkdir ${citer(cible)} && touch ${citer("$cible/x")} && ln -s ${citer(cible)} ${citer(lien)}")
            val entreeLien = entree(navigateur, "/data/local/tmp", "$nom-lien")
            assertTrue("Un lien vers un dossier", entreeLien.lien && entreeLien.dossier)
            val planLien = navigateur.preparerSuppression("/data/local/tmp", entreeLien) as ExamenSuppression.Pret
            assertEquals(NatureSuppression.LIEN, planLien.plan.nature)
            assertEquals(IssueSuppression.SUPPRIME, navigateur.supprimer(planLien.plan).issue)
            val reste = client.executer("[ ! -L ${citer(lien)} ] && [ -f ${citer("$cible/x")} ] && echo intact")
            assertEquals("Le lien est parti, sa cible et son contenu sont là", "intact", reste.sortie)

            // 6. The test folder itself, through the app's code path.
            assertEquals(IssueSuppression.SUPPRIME, navigateur.supprimer(ordinaire.plan).issue)
            assertTrue(navigateur.lister(essai) is LectureDossier.Introuvable)

            // 7. What the shell cannot delete: the TV's error as is. /proc can never be deleted.
            val refus = navigateur.supprimer(PlanSuppression("/proc/version", NatureSuppression.FICHIER))
            println("Effacer /proc/version : $refus")
            assertEquals(IssueSuppression.ECHEC, refus.issue)
        } finally {
            val restes = listOf(essai, "$temporaire-cible", "$temporaire-lien", "$temporaire-noms").joinToString(" ") { citer(it) }
            println("Nettoyage : ${client.executer("rm -rf $restes")}")
            client.deconnecter()
            local.deleteRecursively()
        }
    }

    private suspend fun entree(navigateur: NavigateurFichiers, dossier: String, nom: String): EntreeDistante =
        (navigateur.lister(dossier) as LectureDossier.Lue).entrees.single { it.nom == nom }

    private fun pret(examen: ExamenRapatriement): PlanRapatriement = (examen as ExamenRapatriement.Pret).plan

    private fun empreinte(fichier: File): String =
        MessageDigest.getInstance("MD5").digest(fichier.readBytes()).joinToString("") { "%02x".format(it) }
}
