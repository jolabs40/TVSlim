plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt.android) apply false
}

// Version shared by both apps: appVersion in gradle.properties, or -PappVersion= that CI takes from the
// android-vX.Y.Z tag. versionCode is X*10000 + Y*100 + Z, which grows with the version as long as Y and Z
// stay under 100; Android refuses an update whose versionCode goes down.
val appVersion = providers.gradleProperty("appVersion").get()
val (major, minor, patch) = Regex("""(\d{1,3})\.(\d{1,2})\.(\d{1,2})""").matchEntire(appVersion)?.destructured
    ?: error("invalid appVersion: $appVersion (expected X.Y.Z, Y and Z below 100)")
extra["appVersion"] = appVersion
extra["appVersionCode"] = major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
