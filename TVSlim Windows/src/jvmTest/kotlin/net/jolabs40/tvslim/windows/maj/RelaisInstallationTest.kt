package net.jolabs40.tvslim.windows.maj

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Le relais de mise à jour, exécuté pour de vrai : créé par WMI, il attend la fin des processus
 * indiqués, installe un MSI par-dessus la version en place, puis relance l'application. Ne tourne
 * que sur demande, sur un poste où une version antérieure est installée et ouverte :
 *
 *     ./gradlew jvmTest --tests "*RelaisInstallationTest*" --rerun \
 *         -PrelaisMsi=C:\…\TVSlim-Windows-0.9.1.msi \
 *         -PrelaisExe="C:\Users\…\AppData\Local\TV Slim\TV Slim.exe" \
 *         -PrelaisPid=<pid de l'application>,<pid de son lanceur>
 *
 * Le test rend la main dès que WMI a créé le relais : c'est ensuite en fermant l'application qu'on
 * libère l'installation, et on constate le résultat dans `installation.log`.
 */
class RelaisInstallationTest {

    @Test
    fun `WMI cree le relais qui installera la version suivante`() {
        val msi = System.getProperty("tvslim.relais.msi")
        assumeTrue("-PrelaisMsi=<installateur> pour éprouver le relais", msi != null)
        val executable = System.getProperty("tvslim.relais.exe")?.let(::File)
        val pids = System.getProperty("tvslim.relais.pid").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }

        val accepte = InstallateurMiseAJour.lancerRelais(pids, File(msi!!), executable)

        File(System.getProperty("tvslim.captures"), "relais.txt").apply {
            parentFile.mkdirs()
            writeText("pids attendus=$pids\nrelais accepté par WMI=$accepte\n")
        }
        assertTrue("WMI n'a pas créé le relais", accepte)
    }
}
