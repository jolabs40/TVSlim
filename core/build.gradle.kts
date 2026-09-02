plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    // Le namespace ne sert qu'aux ressources : les classes gardent leurs packages d'origine
    // (net.jolabs40.tvslim.catalog, .journal, .device…), ce qui évite de toucher aux imports.
    namespace = "net.jolabs40.tvslim.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Pas de Hilt ici : le noyau reste instanciable à la main, chaque application le fournit.

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
