plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "net.jolabs40.tvslim"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.jolabs40.tvslim"
        minSdk = 26
        targetSdk = 36
        // Same version for both apps: see the root build.gradle.kts.
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersion"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Passwords come from the KEYSTORE_PASSWORD and KEY_PASSWORD environment variables, never from a
        // file. Keystore path and alias are not secret and live in gradle.properties.
        //
        // Without a password no config is created, so AGP outputs a -release-unsigned.apk instead
        // of failing obscurely at signing time.
        val keystorePath = project.findProperty("KEYSTORE_FILE") as? String
        val password = System.getenv("KEYSTORE_PASSWORD")
            ?: project.findProperty("KEYSTORE_PASSWORD") as? String
        if (keystorePath != null && file(keystorePath).exists() && !password.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = password
                keyAlias = project.findProperty("KEY_ALIAS") as? String ?: ""
                // v1 is useless above API 24 and minSdk is 26. v3 carries the certificate
                // lineage: without it, a compromised key can only be replaced by having
                // everyone uninstall, and sideloading has no Play App Signing to fall back on.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                // With PKCS12 the key shares the store password.
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: project.findProperty("KEY_PASSWORD") as? String ?: password
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
        // Several Compose for TV components are still marked experimental.
        freeCompilerArgs += "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api"
    }
    buildFeatures {
        compose = true
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
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Compose for TV (Material 3)
    implementation(libs.tv.material)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    kapt(libs.hilt.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
