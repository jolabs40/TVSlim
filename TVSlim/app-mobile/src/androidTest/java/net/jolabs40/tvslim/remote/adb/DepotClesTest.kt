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
 * The key store can only be tested on a real device: `EncryptedSharedPreferences` relies on the
 * Android keystore, which a desktop JVM lacks.
 *
 * Losing the private key means re-authorizing debugging with the remote on every TV; leaving it in
 * plaintext gives full shell access to anyone who can read the app's storage.
 */
@RunWith(AndroidJUnit4::class)
class DepotClesTest {

    private val contexte: Context = ApplicationProvider.getApplicationContext()

    /**
     * Dedicated store and folder: these tests run in the app's process, and clearing the real
     * store would lose the user's authorization on their TVs.
     */
    private val ancienDossier get() = File(contexte.filesDir, "adb-test")

    private fun depot() = DepotCles(contexte, COFFRE, ancienDossier)

    @Before
    fun vider() {
        contexte.deleteSharedPreferences(COFFRE)
        ancienDossier.deleteRecursively()
        File(contexte.cacheDir, "cles-temporaires").deleteRecursively()
    }

    /** Reads the encrypted store directly, bypassing `DepotCles`. */
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
    fun key_survives_a_second_request() {
        depot().paire()
        val premiere = auCoffre("publique")

        // A second store instance, as after an app restart.
        depot().paire()

        assertNotNull(premiere)
        assertArrayEquals(
            "Une clé qui change à chaque lancement obligerait à réautoriser chaque téléviseur.",
            premiere,
            auCoffre("publique"),
        )
    }

    @Test
    fun nothing_left_in_plaintext_after_generation() {
        depot().paire()

        assertFalse(File(ancienDossier, "adbkey").exists())
        assertFalse(File(contexte.cacheDir, "cles-temporaires/adbkey").exists())
        assertNotNull("La clé doit bien être au coffre.", auCoffre("privee"))
    }

    @Test
    fun old_plaintext_key_is_migrated_then_deleted() {
        // What an older version left on disk.
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
    fun stored_key_remains_usable_by_dadb() {
        val paire = depot().paire()

        // If the in-memory rebuild were wrong, dadb could not sign the auth request and the TV
        // would refuse the connection.
        assertNotNull(paire)
        assertEquals("RSA", clePriveeDepuisDer(auCoffre("privee")!!).algorithm)
    }

    private companion object {
        const val COFFRE = "cles-adb-test"
    }
}
