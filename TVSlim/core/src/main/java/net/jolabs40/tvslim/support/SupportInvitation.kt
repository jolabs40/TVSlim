package net.jolabs40.tvslim.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.files.UploadResult
import net.jolabs40.tvslim.files.FilesSignal
import net.jolabs40.tvslim.installation.InstallationResult
import net.jolabs40.tvslim.engine.ActionResult

/** What the app remembers about its invitations, stored on the device only. */
data class SupportMemory(
    /** The user said they already donated: taken at their word, never asked again. */
    val donationDeclared: Boolean = false,
    /** When the last invitation was shown; 0 if never. */
    val lastInvitation: Long = 0L,
)

/** Storage for [SupportMemory]: a JSON file on Windows, DataStore on the phone. */
interface SupportStore {
    suspend fun readSupport(): SupportMemory

    suspend fun writeSupport(memory: SupportMemory)
}

/**
 * The invitation to buy a coffee after a successful debloat, file transfer or installation.
 *
 * TV Slim only talks to GitHub (as the README states), so it cannot know who donated. The user says so
 * with a button, and that is final. The support page opens in the browser after a click; the app never
 * calls Ko-fi itself.
 */
object SupportInvitation {
    const val LINK = "https://ko-fi.com/jolabs40"

    /** Minimum time between invitations, so daily use does not mean a daily request. */
    const val INTERVAL_MS: Long = 30L * 24 * 60 * 60 * 1000

    fun shouldOffer(memory: SupportMemory, now: Long): Boolean {
        if (memory.donationDeclared) return false
        if (memory.lastInvitation <= 0L) return true
        val elapsed = now - memory.lastInvitation
        // A clock set backwards must not silence the invitation for years.
        return elapsed < 0 || elapsed >= INTERVAL_MS
    }

    /** A debloat or reapplied configuration where every action succeeded. */
    fun deserves(results: List<ActionResult>): Boolean = results.isNotEmpty() && results.all { it.succeeded }

    /** An upload to the TV or a copy to the PC that completed with no rejected file. */
    fun deserves(upload: UploadResult): Boolean = upload.complete && upload.sentCount > 0

    fun deserves(signal: FilesSignal): Boolean = signal is FilesSignal.Upload && deserves(signal.result)

    fun deserves(installation: InstallationResult): Boolean = installation is InstallationResult.Succeeded
}

/**
 * The support banner, in both apps: shown after a successful action, at most once per
 * [SupportInvitation.INTERVAL_MS], and never again once a donation is declared.
 *
 * The invitation is timestamped when shown, not when dismissed, so closing it without answering does
 * not bring it back on the next action.
 */
class SupportController(
    private val store: SupportStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Called after a successful action; shows the invitation if it is due. */
    fun thank() {
        if (_visible.value) return
        scope.launch {
            val memory = store.readSupport()
            val now = clock()
            if (!SupportInvitation.shouldOffer(memory, now)) return@launch
            store.writeSupport(memory.copy(lastInvitation = now))
            _visible.value = true
        }
    }

    /** "Later", or the support page was opened: hides the banner until the next invitation is due. */
    fun dismiss() {
        _visible.value = false
    }

    /** "I already donated": no more invitations, ever. */
    fun declareDonation() {
        _visible.value = false
        scope.launch { store.writeSupport(store.readSupport().copy(donationDeclared = true)) }
    }
}
