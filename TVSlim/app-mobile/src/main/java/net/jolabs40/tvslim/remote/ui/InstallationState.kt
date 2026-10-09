package net.jolabs40.tvslim.remote.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.installation.FailureCause
import net.jolabs40.tvslim.installation.InstallationResult
import net.jolabs40.tvslim.remote.R
import java.util.Locale

sealed interface InstallationPhase {
    /** Copying the file, reading its manifest and the TV, before confirmation. */
    data object Review : InstallationPhase

    data class Upload(val sent: Long, val total: Long) : InstallationPhase

    /** Upload done; Android is verifying and installing the package. */
    data object Installation : InstallationPhase
}

/** State of the "Install an app" card. */
@Immutable
data class InstallationState(
    val phase: InstallationPhase? = null,
    /** Outcome of the last install, still readable after the banner is gone. */
    val last: InstallationResult? = null,
) {
    val busy: Boolean get() = phase != null
}

@StringRes
fun FailureCause.resource(): Int = when (this) {
    FailureCause.SIGNATURE_MISMATCH -> R.string.apk_cause_signature
    FailureCause.DOWNGRADE -> R.string.apk_cause_downgrade
    FailureCause.ANDROID_TOO_OLD -> R.string.apk_cause_sdk
    FailureCause.ARCHITECTURE -> R.string.apk_cause_abi
    FailureCause.INSUFFICIENT_STORAGE -> R.string.apk_cause_storage
    FailureCause.UNSIGNED -> R.string.apk_cause_unsigned
    FailureCause.INCOMPLETE -> R.string.apk_cause_split
    FailureCause.REJECTED -> R.string.apk_cause_refused
    FailureCause.INVALID -> R.string.apk_cause_invalid
    FailureCause.CONNECTION -> R.string.apk_cause_connection
    FailureCause.OTHER -> R.string.apk_cause_other
}

/** Formats bytes as megabytes with one decimal, using the locale's separator ("48,3" in French). */
fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / 1_000_000.0)
