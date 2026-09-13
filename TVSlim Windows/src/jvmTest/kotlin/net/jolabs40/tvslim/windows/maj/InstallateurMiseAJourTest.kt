package net.jolabs40.tvslim.windows.maj

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

class InstallateurMiseAJourTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private lateinit var serveur: HttpServer
    private val base get() = "http://127.0.0.1:${serveur.address.port}"

    private val cleTvSlim = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val autreCle = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val contenu = ByteArray(50_000) { (it % 97).toByte() }

    private val windows = System.getProperty("os.name").startsWith("Windows")

    private fun signer(octets: ByteArray, version: String, cle: PrivateKey): String {
        val fichier = File(dossier.root, "a-signer").apply { writeBytes(octets) }
        val message = VerificationSignature.message(version, VerificationSignature.empreinte(fichier))
        return Signature.getInstance("Ed25519").run {
            initSign(cle)
            update(message)
            Base64.getEncoder().encodeToString(sign())
        }
    }

    @Before
    fun demarrer() {
        serveur = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fun servir(chemin: String, corps: () -> ByteArray) = serveur.createContext(chemin) { echange ->
            val octets = corps()
            echange.sendResponseHeaders(200, octets.size.toLong())
            echange.responseBody.use { it.write(octets) }
        }
        servir("/msi") { contenu }
        servir("/bonne.sig") { signer(contenu, "1.2.3", cleTvSlim.private).toByteArray() }
        servir("/mauvaise.sig") { signer(contenu, "1.2.3", autreCle.private).toByteArray() }
        serveur.start()
    }

    @After
    fun arreter() = serveur.stop(0)

    private fun maj(signature: String) = MiseAJourDisponible(
        version = Version(1, 2, 3),
        notes = "",
        page = "",
        installateur = FichierPublie("TVSlim-Windows-1.2.3.msi", "$base/msi"),
        signature = FichierPublie("TVSlim-Windows-1.2.3.msi.sig", "$base/$signature"),
        portable = null,
    )

    private fun installateur(telechargements: File) = InstallateurMiseAJour(
        dossier = telechargements,
        client = ClientGithub(depot = "jolabs40/TVSlim", versionApp = "test", api = base),
        clePublique = Base64.getEncoder().encodeToString(cleTvSlim.public.encoded),
    )

    private val msiDelicat =
        File("C:\\Users\\Zoë O'Brien\\AppData\\Local\\TVSlim\\mises-a-jour\\TVSlim-Windows-1.2.3.msi")
    private val exeDelicat = File("C:\\Users\\Zoë O'Brien\\AppData\\Local\\TV Slim\\TV Slim.exe")

    // --- Préparation ----------------------------------------------------------------------------

    @Test
    fun `un installateur bien signe est rendu pret a installer`() = runTest {
        val telechargements = dossier.newFolder("maj")
        var verifie = false

        val msi = installateur(telechargements).preparer(maj("bonne.sig"), {}, { verifie = true })

        assertTrue(verifie)
        assertTrue(msi.readBytes().contentEquals(contenu))
    }

    @Test
    fun `un installateur mal signe est supprime et jamais rendu`() = runTest {
        val telechargements = dossier.newFolder("maj")

        val erreur = runCatching {
            installateur(telechargements).preparer(maj("mauvaise.sig"), {}, {})
        }.exceptionOrNull()

        assertTrue(erreur is InstallateurMiseAJour.SignatureInvalide)
        assertEquals(emptyList<String>(), telechargements.list()!!.filter { it.endsWith(".msi") })
    }

    // --- Relais -----------------------------------------------------------------------------------

    @Test
    fun `le relais attend l'application et son lanceur, installe en silence puis relance`() {
        val script = InstallateurMiseAJour.scriptRelais(listOf(4242, 4243), msiDelicat, exeDelicat)

        assertTrue(script.contains("Wait-Process -Id 4242,4243"))
        assertTrue(script.contains("'/passive'"))
        assertTrue("apostrophe doublée dans un littéral", script.contains("Zoë O''Brien"))
        assertFalse("aucun guillemet double", script.contains('"'))
        assertEquals(2, Regex("Start-Process").findAll(script).count())
    }

    @Test
    fun `sans executable connu, l'installation se fait sans relance`() {
        val script = InstallateurMiseAJour.scriptRelais(listOf(1), File("C:\\x\\a.msi"), null)

        assertEquals(1, Regex("Start-Process").findAll(script).count())
    }

    @Test
    fun `le relais nait par WMI, sans un seul guillemet double sur la ligne de commande`() {
        val script = InstallateurMiseAJour.scriptRelais(listOf(4242), msiDelicat, exeDelicat)
        val commande = InstallateurMiseAJour.commandeLancement(script)

        assertEquals("powershell.exe", commande.first())
        assertTrue(commande.last().contains("Invoke-CimMethod -ClassName Win32_Process -MethodName Create"))
        assertTrue(commande.none { '"' in it })
        assertTrue("apostrophes doublées deux fois", commande.last().contains("Zoë O''''Brien"))
    }

    @Test
    fun `l'application elle-meme figure parmi les processus a attendre`() {
        assertTrue(ProcessHandle.current().pid() in InstallateurMiseAJour.processusAAttendre())
    }

    @Test
    fun `le relais et son lanceur sont des scripts PowerShell valides`() {
        assumeTrue(windows)
        val script = InstallateurMiseAJour.scriptRelais(
            listOf(4242, 4243),
            File(dossier.root, "Zoë O'Brien & co\\TVSlim-Windows-1.2.3.msi"),
            File(dossier.root, "TV Slim\\TV Slim.exe"),
        )
        val lanceur = InstallateurMiseAJour.commandeLancement(script).last()

        listOf("relais" to script, "lanceur" to lanceur).forEach { (nom, contenu) ->
            val fichier = File(dossier.root, "$nom.ps1").apply { writeText(contenu, Charsets.UTF_8) }
            val analyse = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "\$e = \$null; [void][System.Management.Automation.Language.Parser]::ParseFile(" +
                    "'${fichier.absolutePath}', [ref]\$null, [ref]\$e); \$e.Count",
            ).redirectErrorStream(true).start()
            val sortie = analyse.inputStream.bufferedReader().readText().trim()
            analyse.waitFor()
            assertEquals("$nom : aucune erreur d'analyse attendue — $sortie", "0", sortie.lines().last().trim())
        }
    }

    /**
     * Les deux couches de littéraux, traversées pour de vrai : WMI crée un PowerShell qui écrit un
     * fichier dans un dossier dont le nom porte une espace, une apostrophe et un accent.
     */
    @Test
    fun `WMI cree le processus avec la ligne de commande intacte`() {
        assumeTrue(windows)
        val cible = File(dossier.newFolder("Zoë O'Brien"), "temoin.txt")
        val script = "Set-Content -Path '${cible.absolutePath.replace("'", "''")}' -Value 'relais-ok'"

        val lancement = ProcessBuilder(InstallateurMiseAJour.commandeLancement(script)).redirectErrorStream(true).start()
        val sortie = lancement.inputStream.bufferedReader().readText()
        assertEquals("WMI doit accepter la création : $sortie", 0, lancement.waitFor())

        val limite = System.currentTimeMillis() + 20_000
        while (!cible.exists() && System.currentTimeMillis() < limite) Thread.sleep(200)
        assertTrue("le relais créé par WMI n'a rien écrit", cible.exists())
        assertEquals("relais-ok", cible.readText().trim())
    }
}
