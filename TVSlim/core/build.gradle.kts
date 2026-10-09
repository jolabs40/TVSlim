plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    // The namespace only serves resources; classes keep their original packages
    // (net.jolabs40.tvslim.catalog, .journal, .device...) so imports stay unchanged.
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
    // No Hilt: the core stays instantiable by hand, and each app provides it.

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
