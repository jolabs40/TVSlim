package net.jolabs40.tvslim.remote.adb

import net.jolabs40.tvslim.remote.BuildConfig

/**
 * Returns the log detail in debug builds only; release builds still log the event itself.
 *
 * The detail carries the TV's address, the packages being disabled and the permissions granted.
 * None of it is secret (third-party apps cannot read logcat without `READ_LOGS` since Android 11),
 * but release builds should not leak it.
 */
internal fun detail(text: String): String = if (BuildConfig.DEBUG) ": $text" else ""
