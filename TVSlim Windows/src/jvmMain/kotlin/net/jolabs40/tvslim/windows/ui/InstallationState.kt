package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.installation.FailureCause
import net.jolabs40.tvslim.installation.InstallationResult
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.apk_cause_abi
import net.jolabs40.tvslim.windows.resources.apk_cause_connection
import net.jolabs40.tvslim.windows.resources.apk_cause_downgrade
import net.jolabs40.tvslim.windows.resources.apk_cause_invalid
import net.jolabs40.tvslim.windows.resources.apk_cause_other
import net.jolabs40.tvslim.windows.resources.apk_cause_refused
import net.jolabs40.tvslim.windows.resources.apk_cause_sdk
import net.jolabs40.tvslim.windows.resources.apk_cause_signature
import net.jolabs40.tvslim.windows.resources.apk_cause_split
import net.jolabs40.tvslim.windows.resources.apk_cause_storage
import net.jolabs40.tvslim.windows.resources.apk_cause_unsigned
import org.jetbrains.compose.resources.StringResource
import java.util.Locale

sealed interface InstallationPhase {
    /** Reading the file and the TV, before confirmation. */
    data object Review : InstallationPhase

    data class Upload(val sent: Long, val total: Long) : InstallationPhase

    /** Upload done; Android is verifying and installing the app. */
    data object Installation : InstallationPhase
}

/** State of the "Install an app" card, mirroring the companion's. */
@Immutable
data class InstallationState(
    val phase: InstallationPhase? = null,
    /** Outcome of the last install, still readable after the snackbar is gone. */
    val last: InstallationResult? = null,
) {
    val busy: Boolean get() = phase != null
}

fun FailureCause.resource(): StringResource = when (this) {
    FailureCause.SIGNATURE_MISMATCH -> Res.string.apk_cause_signature
    FailureCause.DOWNGRADE -> Res.string.apk_cause_downgrade
    FailureCause.ANDROID_TOO_OLD -> Res.string.apk_cause_sdk
    FailureCause.ARCHITECTURE -> Res.string.apk_cause_abi
    FailureCause.INSUFFICIENT_STORAGE -> Res.string.apk_cause_storage
    FailureCause.UNSIGNED -> Res.string.apk_cause_unsigned
    FailureCause.INCOMPLETE -> Res.string.apk_cause_split
    FailureCause.REJECTED -> Res.string.apk_cause_refused
    FailureCause.INVALID -> Res.string.apk_cause_invalid
    FailureCause.CONNECTION -> Res.string.apk_cause_connection
    FailureCause.OTHER -> Res.string.apk_cause_other
}

/** Bytes as megabytes, one decimal, in the default locale ("48,3" in French). */
fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / 1_000_000.0)
