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
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Les mots de passe viennent de l'environnement, jamais d'un fichier : le coffre
        // les injecte le temps du build, et rien ne les écrit en clair sur le disque.
        //   python ../_tools/secret.py exec KEYSTORE_PASSWORD=tvslim/keystore KEY_PASSWORD=tvslim/keystore -- "C:/Program Files/Git/bin/bash.exe" -c "./gradlew assembleRelease"
        // (secret.py passe par CreateProcess : gradlew étant un script, il lui faut
        //  un shell fils, qui hérite de l'environnement injecté.)
        // Le chemin du keystore et l'alias ne sont pas des secrets : gradle.properties.
        //
        // Sans mot de passe, la configuration n'est pas créée du tout : AGP rend alors
        // un « -release-unsigned.apk » plutôt qu'un échec obscur au moment de signer.
        val keystorePath = project.findProperty("KEYSTORE_FILE") as? String
        val motDePasse = System.getenv("KEYSTORE_PASSWORD")
            ?: project.findProperty("KEYSTORE_PASSWORD") as? String
        if (keystorePath != null && file(keystorePath).exists() && !motDePasse.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = motDePasse
                keyAlias = project.findProperty("KEY_ALIAS") as? String ?: ""
                // v1 est inutile au-dessus de l'API 24, et minSdk vaut 26. v3 porte la
                // lignée de certificat : sans lui, une clé compromise ne se remplace
                // qu'en faisant désinstaller tout le monde — il n'y a pas de Play App
                // Signing pour rattraper, en sideload.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                // En PKCS12, la clé partage le mot de passe du magasin.
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
            // Pas de repli sur la clé de debug : sans keystore, AGP produit un
            // « app-release-unsigned.apk », impossible à installer. L'échec se voit,
            // là où le repli silencieux livrait un APK que la clé publique d'Android
            // Studio, connue de tous, permet de remplacer chez n'importe qui.
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
        // Plusieurs composants de Compose for TV sont encore marqués expérimentaux.
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

    // Compose for TV (Material 3 pour TV)
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
