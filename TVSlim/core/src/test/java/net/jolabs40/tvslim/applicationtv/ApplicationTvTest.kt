package net.jolabs40.tvslim.applicationtv

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.installation.InstallationApk
import net.jolabs40.tvslim.installation.SignatureApkTest
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.InstallateurApk
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Installing the TV app from GitHub. Nothing reaches the TV unless it is signed with our key, then the order is
 * install, grant, launch, start the boot guard.
 */
class ApplicationTvTest {

    private val fixtures = File("src/test/fixtures/apk")

    /** Stateful fake TV: installing adds the app, and permissions granted by `pm grant` show up in `dumpsys`. */
    private class Televiseur(
        var installee: Boolean = false,
        /** Opened once already: Android no longer treats it as stopped, and an update does not change that. */
        var lancee: Boolean = false,
        private val sdk: Int = 34,
        private val reponseGardien: String = "Broadcasting: Intent { … }\nBroadcast completed: result=1",
    ) : ExecuteurCommande, InstallateurApk {
        val commandes = mutableListOf<String>()
        val envois = mutableListOf<File>()
        private val accordees = mutableSetOf<String>()

        override suspend fun executer(commande: String): ResultatShell {
            commandes += commande
            return when {
                commande.startsWith("getprop ro.build.version.sdk;") ->
                    if (installee) ResultatShell(0, "$sdk\n    versionCode=10000 minSdk=26\n    versionName=1.0.0") else ResultatShell(1, "$sdk\n")
                commande == "getprop ro.build.version.sdk" -> ResultatShell(0, "$sdk\n")
                commande == ApplicationTv.COMMANDE_ETAT ->
                    if (installee) ResultatShell(0, "    User 0: ceDataInode=1 installed=true stopped=${!lancee} notLaunched=${!lancee} enabled=0\n    User 0:") else ResultatShell(1, "")
                commande.startsWith("dumpsys package ${ApplicationTv.PAQUET} | grep") ->
                    if (installee) ResultatShell(0, "    versionCode=10000 minSdk=26\n    versionName=1.0.0") else ResultatShell(1, "")
                commande == "dumpsys package ${ApplicationTv.PAQUET}" -> ResultatShell(0, dumpsys())
                commande.startsWith("pm grant ") -> {
                    accordees += commande.substringAfterLast(' ')
                    ResultatShell(0, "")
                }
                commande.startsWith("am broadcast") -> ResultatShell(0, reponseGardien)
                commande.startsWith("am start") -> {
                    lancee = true
                    ResultatShell(0, "")
                }
                else -> ResultatShell(0, "")
            }
        }

        override suspend fun installer(apk: File, surEnvoi: (envoye: Long, total: Long) -> Unit): ResultatShell {
            envois += apk
            surEnvoi(apk.length(), apk.length())
            installee = true
            return ResultatShell(0, "Success")
        }

        private fun dumpsys() = if (!installee) "Unable to find package: ${ApplicationTv.PAQUET}" else buildString {
            appendLine("    requested permissions:")
            appendLine("      ${ApplicationTv.WRITE_SECURE_SETTINGS}")
            appendLine("      ${ApplicationTv.POST_NOTIFICATIONS}")
            appendLine("    install permissions:")
            appendLine("      ${ApplicationTv.WRITE_SECURE_SETTINGS}: granted=${ApplicationTv.WRITE_SECURE_SETTINGS in accordees}")
            appendLine("    runtime permissions:")
            appendLine("      ${ApplicationTv.POST_NOTIFICATIONS}: granted=${ApplicationTv.POST_NOTIFICATIONS in accordees}")
        }
    }

    /** Fake GitHub: one release, serving the given test APK. */
    private inner class Github(
        private val apk: String = "tv-cle-a.apk",
        private val panne: Boolean = false,
    ) : SourcePublications {
        var telechargements = 0

        override suspend fun publications(): String {
            if (panne) throw IOException("hors ligne")
            return """[{"tag_name":"android-v1.1.0","assets":[{"name":"TVSlim-TV-1.1.0.apk",""" +
                """"browser_download_url":"https://github.com/jolabs40/TVSlim/releases/download/android-v1.1.0/TVSlim-TV-1.1.0.apk","size":12375}]}]"""
        }

        override suspend fun telecharger(url: String, cible: File, tailleMax: Long, progression: (Long, Long) -> Unit) {
            telechargements++
            File(fixtures, apk).copyTo(cible, overwrite = true)
            progression(cible.length(), cible.length())
        }
    }

    private val dossier = File.createTempFile("tvslim", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
    private val journal = JournalRepository(File.createTempFile("journal", ".json").also { it.delete() })

    private fun application(tv: Televiseur, github: SourcePublications = Github()) = ApplicationTv(
        executeur = tv,
        installation = InstallationApk(tv, tv, journal),
        moteur = MoteurDebloat(tv, journal),
        lecteur = LecteurDistant(tv),
        source = github,
        empreinteAttendue = SignatureApkTest.CLE_A,
        dossier = dossier,
    )

    @Test
    fun `installs, grants, launches and starts the guard, in that order`() = runTest {
        val tv = Televiseur()
        val etapes = mutableListOf<EtapeTv>()

        val resultat = application(tv).installer { etapes += it }

        assertEquals(ResultatTv.Reussi("1.1.0", autorisee = true, gardien = true), resultat)
        assertEquals(1, tv.envois.size)
        val ordre = listOf("pm grant ${ApplicationTv.PAQUET} ${ApplicationTv.WRITE_SECURE_SETTINGS}", "am start", "am broadcast")
            .map { debut -> tv.commandes.indexOfFirst { it.startsWith(debut) } }
        assertTrue("Ordre : accorder, lancer, gardien — $ordre", ordre.all { it >= 0 } && ordre == ordre.sorted())
        assertTrue("Android 13 et plus : les notifications aussi", tv.commandes.any { it.endsWith(ApplicationTv.POST_NOTIFICATIONS) })
        assertTrue(etapes.first() is EtapeTv.Recherche)
        assertTrue(etapes.any { it is EtapeTv.Verification } && etapes.any { it is EtapeTv.Envoi })
        assertFalse("L'APK téléchargé ne reste pas sur le disque", File(dossier, "tvslim-tv.apk").exists())

        val types = journal.actions.value.map { it.type }
        assertEquals(listOf(TypeAction.INSTALLATION, TypeAction.PERMISSION, TypeAction.PERMISSION), types)
    }

    @Test
    fun `an APK signed with another key is never sent to the TV`() = runTest {
        val tv = Televiseur()

        val resultat = application(tv, Github(apk = "tv-cle-b.apk")).installer()

        assertEquals(MotifTv.CERTIFICAT, (resultat as ResultatTv.Echoue).motif)
        assertEquals(SignatureApkTest.CLE_B, resultat.detail)
        assertTrue(tv.envois.isEmpty())
        assertTrue("Aucune commande n'a modifié le téléviseur", tv.commandes.none { it.startsWith("pm ") || it.startsWith("am ") })
        assertFalse(File(dossier, "tvslim-tv.apk").exists())
    }

    @Test
    fun `an unsigned APK is rejected the same way`() = runTest {
        val tv = Televiseur()
        val resultat = application(tv, Github(apk = "tv-non-signe.apk")).installer()
        assertEquals(MotifTv.CERTIFICAT, (resultat as ResultatTv.Echoue).motif)
        assertTrue(tv.envois.isEmpty())
    }

    @Test
    fun `without GitHub, nothing happens and the reason says so`() = runTest {
        val tv = Televiseur()
        val github = Github(panne = true)
        val resultat = application(tv, github).installer()
        assertEquals(ResultatTv.Echoue(MotifTv.RESEAU), resultat)
        assertEquals(0, github.telechargements)
        assertTrue(tv.commandes.isEmpty())
    }

    @Test
    fun `a TV app that does not know the command does not confirm its guard`() = runTest {
        val tv = Televiseur(reponseGardien = "Broadcast completed: result=0")
        val resultat = application(tv).installer()
        assertEquals(ResultatTv.Reussi("1.1.0", autorisee = true, gardien = false), resultat)
    }

    @Test
    fun `before Android 13, no notification permission is granted`() = runTest {
        val tv = Televiseur(sdk = 30)
        application(tv).installer()
        assertTrue(tv.commandes.none { it.endsWith(ApplicationTv.POST_NOTIFICATIONS) })
    }

    @Test
    fun `the status compares what the TV has with what GitHub offers`() = runTest {
        val disponible = application(Televiseur()).derniere()
        assertEquals("1.1.0", disponible?.version)
        assertEquals(null, application(Televiseur(), Github(panne = true)).derniere())

        assertEquals(EtatApplicationTv.ABSENTE, application(Televiseur()).situation(disponible).etat)

        val ancienne = application(Televiseur(installee = true)).situation(disponible)
        assertEquals(10000L, ancienne.installee?.versionCode)
        assertEquals(EtatApplicationTv.MISE_A_JOUR, ancienne.etat)

        val sansGithub = application(Televiseur(installee = true)).situation(disponible = null)
        assertEquals("Sans GitHub, l'autorisation manquante se dit encore", EtatApplicationTv.SANS_AUTORISATION, sansGithub.etat)
    }

    @Test
    fun `granting an app already installed downloads nothing`() = runTest {
        val tv = Televiseur(installee = true)
        val github = Github()

        val resultat = application(tv, github).autoriser("1.0.0")

        assertEquals(ResultatTv.Reussi("1.0.0", autorisee = true, gardien = true), resultat)
        assertEquals(0, github.telechargements)
        assertTrue(tv.envois.isEmpty())
        assertEquals(EtatApplicationTv.A_JOUR, application(tv).situation(disponible = null).etat)
    }

    @Test
    fun `an update does not open an app already launched, so the TV keeps its program`() = runTest {
        val tv = Televiseur(installee = true, lancee = true)

        val resultat = application(tv).installer()

        assertEquals(ResultatTv.Reussi("1.1.0", autorisee = true, gardien = true), resultat)
        assertEquals(1, tv.envois.size)
        assertTrue("Rien ne passe au premier plan : ${tv.commandes}", tv.commandes.none { it.startsWith("am start") })
        assertTrue(tv.commandes.any { it.startsWith("am broadcast") })
    }

    @Test
    fun `granting an app already launched does not open it either`() = runTest {
        val tv = Televiseur(installee = true, lancee = true)
        application(tv).autoriser("1.0.0")
        assertTrue(tv.commandes.none { it.startsWith("am start") })
    }

    @Test
    fun `only the main profile decides whether the app is stopped`() {
        // Captured on the TCL: the second profile, never opened, reports the app as stopped.
        val tcl = """
            |    User 0: ceDataInode=1332869 installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
            |    User 10: ceDataInode=0 installed=true hidden=false suspended=false distractionFlags=0 stopped=true notLaunched=true enabled=0 instant=false virtual=false
            |    User 0:
        """.trimMargin()
        assertFalse(ApplicationTv.arretee(tcl))
        assertTrue(ApplicationTv.arretee("    User 0: ceDataInode=1 installed=true stopped=true notLaunched=true enabled=0"))
        assertTrue("Jamais ouverte, même non arrêtée de force", ApplicationTv.arretee("    User 0: installed=true stopped=false notLaunched=true"))
        assertTrue("Illisible : on la lance, comme avant", ApplicationTv.arretee(""))
    }
}
