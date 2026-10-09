plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "net.jolabs40.tvslim.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.jolabs40.tvslim.remote"
        // dadb needs Java 8+ and modern network APIs; 26 is our usual floor anyway.
        minSdk = 26
        targetSdk = 36
        // Same version for both apps: see the root build.gradle.kts.
        versionCode = rootProject.extra["codeDeVersion"] as Int
        versionName = rootProject.extra["versionApp"] as String
        // Expected certificate of the TV APK downloaded from GitHub, the one CI checks before publishing.
        // An APK without it is never sent to the TV.
        buildConfigField(
            "String",
            "EMPREINTE_CERTIFICAT",
            "\"${providers.gradleProperty("empreinteCertificat").get()}\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Passwords come from the KEYSTORE_PASSWORD and KEY_PASSWORD environment variables, never from a
        // file. Keystore path and alias are not secret and live in gradle.properties.
        //
        // Without a password no config is created, so AGP outputs a -release-unsigned.apk instead
        // of failing obscurely at signing time.
        val keystorePath = project.findProperty("KEYSTORE_FILE") as? String
        val motDePasse = System.getenv("KEYSTORE_PASSWORD")
            ?: project.findProperty("KEYSTORE_PASSWORD") as? String
        if (keystorePath != null && file(keystorePath).exists() && !motDePasse.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = motDePasse
                keyAlias = project.findProperty("KEY_ALIAS") as? String ?: ""
                // v1 is useless above API 24 and minSdk is 26. v3 carries the certificate
                // lineage: without it, a compromised key can only be replaced by having
                // everyone uninstall, and sideloading has no Play App Signing to fall back on.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                // With PKCS12 the key shares the store password.
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: project.findProperty("KEY_PASSWORD") as? String ?: motDePasse
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // No fallback to the debug key: without a keystore AGP produces an uninstallable
            // app-release-unsigned.apk. A silent fallback would ship an APK that anyone could
            // replace, since Android Studio's debug key is public.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG: detailed traces in debug builds only (see adb/Traces.kt).
        buildConfig = true
    }
    packaging {
        resources {
            // dadb bundles Bouncy Castle and Okio, whose metadata files collide.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    kapt(libs.hilt.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ADB client: the phone talks to the TV without an adb binary or ADB server.
    implementation(libs.dadb)
    implementation(libs.security.crypto)
    implementation(libs.code.scanner)
    implementation(libs.play.services.base)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
