// Aucun plugin appliqué à la racine : chaque module déclare les siens.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt.android) apply false
}

// La version des deux applications, partagée : versionApp dans gradle.properties, ou -PversionApp= que la
// CI tire du tag android-vX.Y.Z. Le versionCode en découle, X·10 000 + Y·100 + Z : il croît avec la version
// tant que Y et Z restent sous 100 — Android refuse une mise à jour dont le versionCode descend.
val versionApp = providers.gradleProperty("versionApp").get()
val (majeur, mineur, correctif) = Regex("""(\d{1,3})\.(\d{1,2})\.(\d{1,2})""").matchEntire(versionApp)?.destructured
    ?: error("versionApp invalide : $versionApp (attendu X.Y.Z, Y et Z sous 100)")
extra["versionApp"] = versionApp
extra["codeDeVersion"] = majeur.toInt() * 10_000 + mineur.toInt() * 100 + correctif.toInt()
