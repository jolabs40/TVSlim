package net.jolabs40.tvslim.installation

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.InstallateurApk
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** APK installation: what is read from the TV before confirming, what never reaches its shell, and what is logged. */
class InstallationApkTest {

    /** Fake TV that records commands and uploads and returns canned replies. */
    private class Televiseur(
        private val lecture: (String) -> ResultatShell = { ResultatShell(1, "34") },
        private val reponseInstallation: ResultatShell = ResultatShell(0, "Success"),
    ) : ExecuteurCommande, InstallateurApk {
        val commandes = mutableListOf<String>()
        val envois = mutableListOf<File>()

        override suspend fun executer(commande: String): ResultatShell {
            commandes += commande
            return lecture(commande)
        }

        override suspend fun installer(apk: File, surEnvoi: (envoye: Long, total: Long) -> Unit): ResultatShell {
            envois += apk
            surEnvoi(apk.length() / 2, apk.length())
            surEnvoi(apk.length(), apk.length())
            return reponseInstallation
        }
    }

    private val fichier = File.createTempFile("tvslim", ".apk").apply {
        deleteOnExit()
        writeBytes(ByteArray(2048))
    }

    private val manifeste = ManifesteApk("net.jolabs40.hippietv", versionCode = 240, versionName = "2.4.0", minSdk = 26)

    private fun journal() = JournalRepository(File.createTempFile("journal", ".json").also { it.delete() })

    private fun apk(installee: VersionInstallee? = null) =
        ApkChoisi(fichier, "HippieTV.apk", fichier.length(), manifeste, installee)

    @Test
    fun `an app missing from the tv is a new install, and nothing is uploaded`() = runTest {
        val tv = Televiseur(lecture = { ResultatShell(1, "34\n") })

        val examen = InstallationApk(tv, tv, journal()).examiner(fichier, "HippieTV.apk", manifeste)

        val choisi = (examen as ExamenApk.Pret).apk
        assertNull(choisi.installee)
        assertEquals(NatureInstallation.NOUVELLE, choisi.nature)
        assertEquals(2048L, choisi.taille)
        assertEquals(1, tv.commandes.size)
        assertTrue("Examiner n'envoie rien", tv.envois.isEmpty())
    }

    @Test
    fun `the installed version is read before the factory version hidden behind it`() = runTest {
        val sortie = listOf(
            "34",
            "    versionCode=251 minSdk=26 targetSdk=35",
            "    versionName=2.5.1 beta",
            "    versionCode=12 minSdk=21 targetSdk=28",
            "    versionName=1.0",
        ).joinToString("\n")
        val tv = Televiseur(lecture = { ResultatShell(0, sortie) })

        val choisi = (InstallationApk(tv, tv, journal()).examiner(fichier, "HippieTV.apk", manifeste) as ExamenApk.Pret).apk

        assertEquals(VersionInstallee(251, "2.5.1 beta"), choisi.installee)
        assertEquals(NatureInstallation.RETROGRADATION, choisi.nature)
    }

    @Test
    fun `update and reinstall are told apart by the version code`() {
        assertEquals(NatureInstallation.MISE_A_JOUR, apk(VersionInstallee(239, "2.3.9")).nature)
        assertEquals(NatureInstallation.REINSTALLATION, apk(VersionInstallee(240, "2.4.0")).nature)
    }

    @Test
    fun `a too old Android is refused before any upload`() = runTest {
        val tv = Televiseur(lecture = { ResultatShell(1, "25") })

        val examen = InstallationApk(tv, tv, journal()).examiner(fichier, "HippieTV.apk", manifeste)

        assertEquals(ExamenApk.Refuse(RefusApk.ANDROID_TROP_ANCIEN, minSdk = 26, sdkTeleviseur = 25), examen)
        assertTrue(tv.envois.isEmpty())
    }

    @Test
    fun `a forged package name never reaches the shell`() = runTest {
        val tv = Televiseur()

        val examen = InstallationApk(tv, tv, journal())
            .examiner(fichier, "piege.apk", manifeste.copy(paquet = "x; reboot"))

        assertEquals(ExamenApk.Refuse(RefusApk.PAQUET_INVALIDE), examen)
        assertTrue(tv.commandes.isEmpty())
    }

    @Test
    fun `an unreachable tv is reported as such`() = runTest {
        val tv = Televiseur(lecture = { ResultatShell.indisponible("Aucun téléviseur connecté.") })

        val examen = InstallationApk(tv, tv, journal()).examiner(fichier, "HippieTV.apk", manifeste)

        assertEquals(ExamenApk.Refuse(RefusApk.TELEVISEUR_INJOIGNABLE), examen)
    }

    @Test
    fun `a file that is not an APK is refused without querying the tv`() = runTest {
        val tv = Televiseur()

        val examen = InstallationApk(tv, tv, journal()).examiner(fichier, "HippieTV.apk")

        assertEquals(ExamenApk.Refuse(RefusApk.PAS_UN_APK), examen)
        assertTrue(tv.commandes.isEmpty())
    }

    @Test
    fun `a successful install is logged, with no undo command and no uninstall`() = runTest {
        val tv = Televiseur()
        val carnet = journal()
        val avancement = mutableListOf<Long>()

        val resultat = InstallationApk(tv, tv, carnet).installer(apk()) { envoye, _ -> avancement += envoye }

        assertEquals(ResultatInstallation.Reussie(apk()), resultat)
        assertEquals(listOf(1024L, 2048L), avancement)
        val action = carnet.actions.value.single()
        assertEquals(TypeAction.INSTALLATION, action.type)
        assertEquals("net.jolabs40.hippietv", action.cible)
        assertEquals("Installation de HippieTV.apk (2.4.0)", action.libelle)
        assertEquals("", action.commandeAnnulation)
        assertTrue(action.reussi)
        assertFalse("Aucun uninstall, jamais", tv.commandes.any { it.contains("uninstall") })
    }

    @Test
    fun `an Android refusal is logged as a failure, with its cause and raw output`() = runTest {
        val refus = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package net.jolabs40.hippietv signatures do not " +
            "match newer version; ignoring!]"
        val tv = Televiseur(reponseInstallation = ResultatShell(1, refus))
        val carnet = journal()

        val resultat = InstallationApk(tv, tv, carnet).installer(apk())

        val echec = resultat as ResultatInstallation.Echouee
        assertEquals(CauseEchec.SIGNATURE_DIFFERENTE, echec.cause)
        assertEquals(refus, echec.detail)
        with(carnet.actions.value.single()) {
            assertFalse(reussi)
            assertEquals(refus, message)
        }
    }

    @Test
    fun `common Android refusals are recognized`() {
        mapOf(
            "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]" to CauseEchec.RETROGRADATION,
            "Failure [INSTALL_FAILED_OLDER_SDK: Requires newer sdk version #34 (current version is #30)]" to
                CauseEchec.ANDROID_TROP_ANCIEN,
            "Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]" to
                CauseEchec.ARCHITECTURE,
            "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]" to CauseEchec.ESPACE,
            "Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: No signature found in package]" to CauseEchec.NON_SIGNE,
            "Failure [INSTALL_FAILED_MISSING_SPLIT: Missing split for net.jolabs40.hippietv]" to CauseEchec.INCOMPLET,
            "Failure [INSTALL_FAILED_VERIFICATION_FAILURE]" to CauseEchec.REFUSEE,
            "Failure [INSTALL_PARSE_FAILED_NOT_APK: Failed to parse base.apk]" to CauseEchec.INVALIDE,
            "Failure [INSTALL_FAILED_INTERNAL_ERROR: Session relinquished]" to CauseEchec.AUTRE,
            "Error: java.lang.SecurityException" to CauseEchec.AUTRE,
        ).forEach { (sortie, cause) ->
            assertEquals(sortie, cause, InstallationApk.causeEchec(ResultatShell(1, sortie)))
        }
        assertEquals(CauseEchec.CONNEXION, InstallationApk.causeEchec(ResultatShell.indisponible("délai dépassé")))
    }
}
