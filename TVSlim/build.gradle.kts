plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt.android) apply false
}

// Version shared by both apps: versionApp in gradle.properties, or -PversionApp= that CI takes from the
// android-vX.Y.Z tag. versionCode is X*10000 + Y*100 + Z, which grows with the version as long as Y and Z
// stay under 100; Android refuses an update whose versionCode goes down.
val versionApp = providers.gradleProperty("versionApp").get()
val (majeur, mineur, correctif) = Regex("""(\d{1,3})\.(\d{1,2})\.(\d{1,2})""").matchEntire(versionApp)?.destructured
    ?: error("versionApp invalide : $versionApp (attendu X.Y.Z, Y et Z sous 100)")
extra["versionApp"] = versionApp
extra["codeDeVersion"] = majeur.toInt() * 10_000 + mineur.toInt() * 100 + correctif.toInt()
