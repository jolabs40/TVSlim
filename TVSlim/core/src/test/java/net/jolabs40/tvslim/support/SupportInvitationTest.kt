package net.jolabs40.tvslim.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.jolabs40.tvslim.files.UploadFailure
import net.jolabs40.tvslim.files.UploadResult
import net.jolabs40.tvslim.files.TransferDirection
import net.jolabs40.tvslim.files.FilesSignal
import net.jolabs40.tvslim.installation.ChosenApk
import net.jolabs40.tvslim.installation.FailureCause
import net.jolabs40.tvslim.installation.ApkManifest
import net.jolabs40.tvslim.installation.InstallationResult
import net.jolabs40.tvslim.engine.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * When the support invitation shows, and what earns it. Both apps rely on these rules: it must never come back
 * daily, nor after a declared donation.
 */
class SupportInvitationTest {

    private val day = 24L * 60 * 60 * 1000
    private val start = 1_790_000_000_000L

    @Test
    fun `the first invitation shows, the next one waits thirty days`() {
        assertTrue(SupportInvitation.shouldOffer(SupportMemory(), start))

        val shown = SupportMemory(lastInvitation = start)
        assertFalse(SupportInvitation.shouldOffer(shown, start + 29 * day))
        assertTrue(SupportInvitation.shouldOffer(shown, start + 30 * day))
    }

    @Test
    fun `a declared donation silences the invitation for good`() {
        val donated = SupportMemory(donationDeclared = true)

        assertFalse(SupportInvitation.shouldOffer(donated, start))
        assertFalse(SupportInvitation.shouldOffer(donated.copy(lastInvitation = start), start + 400 * day))
    }

    @Test
    fun `a clock set backwards does not block the invitation`() {
        assertTrue(SupportInvitation.shouldOffer(SupportMemory(lastInvitation = start), start - day))
    }

    @Test
    fun `only a batch where every action succeeded earns a thank you`() {
        val ok = ActionResult("com.tcl.ad", "Ads", succeeded = true)
        val failed = ActionResult("com.tcl.x", "Refused", succeeded = false, message = "protected")

        assertTrue(SupportInvitation.deserves(listOf(ok, ok.copy(packageName = "com.tcl.b"))))
        assertFalse(SupportInvitation.deserves(listOf(ok, failed)))
        assertFalse(SupportInvitation.deserves(emptyList<ActionResult>()))
    }

    @Test
    fun `a transfer earns a thank you if it completed, in either direction`() {
        val upload = UploadResult(destination = "/sdcard/Download", sentCount = 3, count = 3)

        assertTrue(SupportInvitation.deserves(upload))
        assertTrue(SupportInvitation.deserves(upload.copy(direction = TransferDirection.DOWNLOAD)))
        assertFalse(SupportInvitation.deserves(upload.copy(cancelled = true)))
        assertFalse(SupportInvitation.deserves(upload.copy(interrupted = true)))
        assertFalse(SupportInvitation.deserves(upload.copy(sentCount = 2, failures = listOf(UploadFailure("a.txt", "refused")))))
        assertFalse(SupportInvitation.deserves(upload.copy(sentCount = 0, count = 0)))

        assertTrue(SupportInvitation.deserves(FilesSignal.Upload(upload)))
        assertFalse(SupportInvitation.deserves(FilesSignal.Busy))
    }

    @Test
    fun `only a successful install earns a thank you`() {
        val apk = ChosenApk(
            file = File("HippieTV.apk"),
            name = "HippieTV.apk",
            size = 1L,
            manifest = ApkManifest("net.jolabs40.hippietv", 1, "1.0", 26),
            installed = null,
        )

        assertTrue(SupportInvitation.deserves(InstallationResult.Succeeded(apk)))
        assertFalse(SupportInvitation.deserves(InstallationResult.Failed(apk, FailureCause.SIGNATURE_MISMATCH, "")))
    }

    @Test
    fun `the banner shows, records its date, and does not return before its time`() {
        val store = InMemoryStore()
        var now = start
        val controller = SupportController(store, CoroutineScope(Dispatchers.Unconfined)) { now }

        controller.thank()
        assertTrue(controller.visible.value)
        assertEquals(start, store.memory.lastInvitation)

        controller.dismiss()
        now += 10 * day
        controller.thank()
        assertFalse(controller.visible.value)

        now += 20 * day
        controller.thank()
        assertTrue(controller.visible.value)
    }

    @Test
    fun `after a declared donation, the banner never returns`() {
        val store = InMemoryStore()
        var now = start
        val controller = SupportController(store, CoroutineScope(Dispatchers.Unconfined)) { now }

        controller.thank()
        controller.declareDonation()
        assertFalse(controller.visible.value)
        assertTrue(store.memory.donationDeclared)

        now += 400 * day
        controller.thank()
        assertFalse(controller.visible.value)
    }

    private class InMemoryStore(var memory: SupportMemory = SupportMemory()) : SupportStore {
        override suspend fun readSupport(): SupportMemory = memory

        override suspend fun writeSupport(memory: SupportMemory) {
            this.memory = memory
        }
    }
}
