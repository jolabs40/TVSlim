package net.jolabs40.tvslim.device

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lecture de la mémoire.
 *
 * La sortie utilisée ici est celle relevée sur la Shield : ses séparateurs de milliers, ses
 * suffixes en kilo-octets et ses processus système au nom sans point, qui ne doivent pas se
 * voir proposer un bouton « Arrêter ».
 */
class LecteurMemoireTest {

    private class ExecuteurFixe(private val sortie: String, private val code: Int = 0) :
        ExecuteurCommande {
        override suspend fun executer(commande: String) = ResultatShell(code, sortie)
    }

    private val sortieShield = """
        Total PSS by process:
            183,873K: system (pid 3703 state 0 oom -900)
            161,015K: com.spocky.projengmenu (pid 4799 state 14 oom 150 / activities)
            137,665K: vendor.nvidia.hardware.graphics.composer@2.0-service (pid 3387)
            128,382K: com.google.android.tts (pid 6970 state 4 oom 200)
             84,036K: surfaceflinger (pid 3407)
             38,580K: com.android.vending:background (pid 6822 state 19 oom 955)

        Total PSS by OOM adjustment:
            183,873K: Native
        Total RAM: 3,016,708K (status normal)
         Free RAM: 1,097,170K (  431,230K cached pss +   552,380K cached kernel +   113,560K free)
         Used RAM: 1,769,972K (1,540,296K used pss +   229,676K kernel)
         Lost RAM:   208,199K
             ZRAM:    18,728K physical used for    80,232K in swap (  524,284K total swap)
    """.trimIndent()

    @Test
    fun `les totaux sont lus malgre les separateurs de milliers`() = runTest {
        val memoire = LecteurDistant(ExecuteurFixe(sortieShield)).memoire()

        assertTrue(memoire.renseignee)
        assertEquals(3_016_708L, memoire.totalKo)
        assertEquals(1_097_170L, memoire.libreKo)
        assertEquals(1_769_972L, memoire.utiliseeKo)
        assertEquals(431_230L, memoire.cacheKo)
        assertEquals(18_728L, memoire.zramKo)
    }

    @Test
    fun `les processus sont classes du plus gourmand au moins gourmand`() = runTest {
        val memoire = LecteurDistant(ExecuteurFixe(sortieShield)).memoire()

        assertEquals(6, memoire.processus.size)
        assertEquals("system", memoire.processus.first().nom)
        assertEquals(179, memoire.processus.first().megaoctets)
        assertEquals(3703, memoire.processus.first().pid)

        val poids = memoire.processus.map { it.kilooctets }
        assertEquals(poids.sortedDescending(), poids)
    }

    @Test
    fun `la section OOM n'est pas confondue avec la liste des processus`() = runTest {
        val memoire = LecteurDistant(ExecuteurFixe(sortieShield)).memoire()

        // « 183,873K: Native » appartient au second tableau : le compter ferait un doublon.
        assertEquals(1, memoire.processus.count { it.kilooctets == 183_873L })
        assertTrue(memoire.processus.none { it.nom == "Native" })
    }

    @Test
    fun `seuls les processus applicatifs peuvent etre arretes`() = runTest {
        val memoire = LecteurDistant(ExecuteurFixe(sortieShield)).memoire()
        fun processus(nom: String) = memoire.processus.first { it.nom == nom }

        assertTrue(processus("com.spocky.projengmenu").estUneApplication)
        assertFalse("system n'est pas une application", processus("system").estUneApplication)
        assertFalse("surfaceflinger non plus", processus("surfaceflinger").estUneApplication)
        assertFalse(
            "un service du fabricant non plus",
            processus("vendor.nvidia.hardware.graphics.composer@2.0-service").estUneApplication,
        )
    }

    @Test
    fun `un processus secondaire renvoie au paquet qui le porte`() = runTest {
        val memoire = LecteurDistant(ExecuteurFixe(sortieShield)).memoire()
        val arrierePlan = memoire.processus.first { it.nom == "com.android.vending:background" }

        // On arrête « com.android.vending », pas « com.android.vending:background ».
        assertEquals("com.android.vending", arrierePlan.paquet)
        assertTrue(arrierePlan.estUneApplication)
    }

    @Test
    fun `une lecture en echec ne renvoie pas de chiffres inventes`() = runTest {
        val memoire = LecteurDistant(ExecuteurFixe("", code = 1)).memoire()

        assertFalse(memoire.renseignee)
        assertTrue(memoire.processus.isEmpty())
    }
}
