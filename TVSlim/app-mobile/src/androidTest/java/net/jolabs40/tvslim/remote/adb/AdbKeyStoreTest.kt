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
class AdbKeyStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Dedicated store and folder: these tests run in the app's process, and clearing the real
     * store would lose the user's authorization on their TVs.
     */
    private val oldFolder get() = File(context.filesDir, "adb-test")

    private fun keyStore() = AdbKeyStore(context, VAULT, oldFolder)

    @Before
    fun clear() {
        context.deleteSharedPreferences(VAULT)
        oldFolder.deleteRecursively()
        File(context.cacheDir, "cles-temporaires").deleteRecursively()
    }

    /** Reads the encrypted store directly, bypassing `AdbKeyStore`. */
    private fun inVault(name: String): ByteArray? {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            VAULT,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return prefs.getString(name, null)?.let { Base64.getDecoder().decode(it) }
    }

    @Test
    fun key_survives_a_second_request() {
        keyStore().pair()
        val first = inVault("publique")

        // A second store instance, as after an app restart.
        keyStore().pair()

        assertNotNull(first)
        assertArrayEquals(
            "A key that changes on every launch would force re-authorizing every TV.",
            first,
            inVault("publique"),
        )
    }

    @Test
    fun nothing_left_in_plaintext_after_generation() {
        keyStore().pair()

        assertFalse(File(oldFolder, "adbkey").exists())
        assertFalse(File(context.cacheDir, "cles-temporaires/adbkey").exists())
        assertNotNull("The key must be in the vault.", inVault("privee"))
    }

    @Test
    fun old_plaintext_key_is_migrated_then_deleted() {
        // What an older version left on disk.
        oldFolder.mkdirs()
        val privateKey = File(oldFolder, "adbkey")
        val publicKey = File(oldFolder, "adbkey.pub")
        AdbKeyPair.generate(privateKey, publicKey)
        val expected = publicKey.readBytes()
        val expectedDer = derFromPem(privateKey.readText())

        keyStore().pair()

        assertArrayEquals(
            "The key already authorized on the TVs must be kept as is.",
            expected,
            inVault("publique"),
        )
        assertArrayEquals(expectedDer, inVault("privee"))
        assertFalse("The plaintext key must be gone once it is in the vault.", privateKey.exists())
        assertFalse(publicKey.exists())
    }

    @Test
    fun stored_key_remains_usable_by_dadb() {
        val pair = keyStore().pair()

        // If the in-memory rebuild were wrong, dadb could not sign the auth request and the TV
        // would refuse the connection.
        assertNotNull(pair)
        assertEquals("RSA", privateKeyFromDer(inVault("privee")!!).algorithm)
    }

    private companion object {
        const val VAULT = "cles-adb-test"
    }
}
