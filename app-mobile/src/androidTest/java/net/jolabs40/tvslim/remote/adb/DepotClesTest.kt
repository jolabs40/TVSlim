package net.jolabs40.tvslim.remote.adb

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dadb.AdbKeyPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * Le coffre de clés ne se vérifie que sur un vrai appareil : `EncryptedSharedPreferences`
 * s'appuie sur le keystore Android, absent d'une JVM de bureau.
 *
 * Ce qui se joue ici n'est pas cosmétique. Perdre la clé privée, c'est devoir réautoriser le
 * débogage à la télécommande sur chaque téléviseur ; la laisser en clair, c'est offrir un accès
 * shell complet à qui lit le stockage de l'application.
 */
@RunWith(AndroidJUnit4::class)
class DepotClesTest {

    private val contexte: Context = ApplicationProvider.getApplicationContext()

    /**
     * Coffre et dossier dédiés : ces tests tournent dans le processus de l'application, et
     * vider le coffre réel ferait perdre à la personne l'autorisation de ses téléviseurs.
     */
    private val ancienDossier get() = File(contexte.filesDir, "adb-test")

    private fun depot() = DepotCles(contexte, COFFRE, ancienDossier)

    @Before
    fun vider() {
        contexte.deleteSharedPreferences(COFFRE)
        ancienDossier.deleteRecursively()
        File(contexte.cacheDir, "cles-temporaires").deleteRecursively()
    }

    /** Relit le coffre comme le ferait l'application, sans passer par le dépôt. */
    private fun auCoffre(nom: String): ByteArray? {
        val cleMaitre = MasterKey.Builder(contexte)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            contexte,
            COFFRE,
            cleMaitre,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return prefs.getString(nom, null)?.let { Base64.getDecoder().decode(it) }
    }

    @Test
    fun la_cle_survit_a_une_seconde_demande() {
        depot().paire()
        val premiere = auCoffre("publique")

        // Un second dépôt, comme après un redémarrage de l'application.
        depot().paire()

        assertNotNull(premiere)
        assertArrayEquals(
            "Une clé qui change à chaque lancement obligerait à réautoriser chaque téléviseur.",
            premiere,
            auCoffre("publique"),
        )
    }

    @Test
    fun rien_ne_traine_en_clair_apres_generation() {
        depot().paire()

        assertFalse(File(ancienDossier, "adbkey").exists())
        assertFalse(File(contexte.cacheDir, "cles-temporaires/adbkey").exists())
        assertNotNull("La clé doit bien être au coffre.", auCoffre("privee"))
    }

    @Test
    fun une_ancienne_cle_en_clair_est_reprise_puis_effacee() {
        // Ce qu'une version précédente laissait sur le disque.
        ancienDossier.mkdirs()
        val privee = File(ancienDossier, "adbkey")
        val publique = File(ancienDossier, "adbkey.pub")
        AdbKeyPair.generate(privee, publique)
        val attendue = publique.readBytes()
        val derAttendu = derDepuisPem(privee.readText())

        depot().paire()

        assertArrayEquals(
            "La clé déjà autorisée sur les téléviseurs doit être conservée telle quelle.",
            attendue,
            auCoffre("publique"),
        )
        assertArrayEquals(derAttendu, auCoffre("privee"))
        assertFalse("La clé en clair doit disparaître une fois au coffre.", privee.exists())
        assertFalse(publique.exists())
    }

    @Test
    fun la_cle_du_coffre_reste_utilisable_par_dadb() {
        val paire = depot().paire()

        // Si la reconstruction en mémoire était fautive, dadb ne saurait pas signer la
        // demande d'authentification et le téléviseur refuserait la connexion.
        assertNotNull(paire)
        assertEquals("RSA", clePriveeDepuisDer(auCoffre("privee")!!).algorithm)
    }

    private companion object {
        const val COFFRE = "cles-adb-test"
    }
}
