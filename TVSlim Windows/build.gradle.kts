import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

// Version et dépôt se lisent dans gradle.properties ; la CI remplace la version par celle du tag.
val versionApp: String = providers.gradleProperty("versionApp").get()
val depotGithub: String = providers.gradleProperty("depotGithub").get()
val clePubliqueMisesAJour: String = providers.gradleProperty("clePubliqueMisesAJour").get()
val licenceApp: String = providers.gradleProperty("licenceApp").get()
version = versionApp

/**
 * Le noyau de l'application Android, compilé ici **depuis ses propres sources** : catalogue,
 * moteur et garde-fous n'existent qu'en un exemplaire, et leurs tests tournent dans les deux
 * builds. Seul `CatalogueRepository.kt` est écarté — il lit ses fichiers par un `Context` Android —
 * et remplacé par `CatalogueRepositoryJvm.kt`, qui lit les mêmes dans le classpath.
 */
val noyau = layout.projectDirectory.dir("../TVSlim/core")

/** Version et dépôt, lisibles depuis le code sans dépendre du lanceur jpackage. */
val genererInfosApp by tasks.registering {
    val dossier = layout.buildDirectory.dir("generated/infosApp/kotlin")
    val version = versionApp
    val depot = depotGithub
    val clePublique = clePubliqueMisesAJour
    val licence = licenceApp
    inputs.property("version", version)
    inputs.property("depot", depot)
    inputs.property("clePublique", clePublique)
    inputs.property("licence", licence)
    outputs.dir(dossier)
    doLast {
        val fichier = dossier.get().file("net/jolabs40/tvslim/windows/InfosApp.kt").asFile
        fichier.parentFile.mkdirs()
        fichier.writeText(
            """
            |package net.jolabs40.tvslim.windows
            |
            |/** Généré par la tâche Gradle `genererInfosApp` : ne pas modifier à la main. */
            |internal object InfosApp {
            |    const val VERSION = "$version"
            |    const val DEPOT_GITHUB = "$depot"
            |    const val CLE_PUBLIQUE_MISES_A_JOUR = "$clePublique"
            |    const val LICENCE = "$licence"
            |}
            |""".trimMargin(),
        )
    }
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        // Les textes et les icônes vivent dans commonMain/composeResources : la classe Res y est
        // générée, et les bibliothèques qu'elle appelle doivent donc y être déclarées.
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
        }
        jvmMain {
            kotlin.srcDir(genererInfosApp)
            kotlin.srcDir(noyau.dir("src/main/java"))
            kotlin.exclude("**/catalog/CatalogueRepository.kt")
            resources.srcDir(noyau.dir("src/main/assets"))

            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.dadb)
                implementation(libs.jmdns)
                implementation(libs.jna.platform)
            }
        }
        jvmTest {
            kotlin.srcDir(noyau.dir("src/test/java"))
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.compose.ui.test.junit4)
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    // TraductionsTest ouvre `src/main/assets/…` par un chemin relatif : les tests tournent depuis
    // le dossier du noyau, exactement comme dans le build Android. Ceux propres à Windows ne
    // travaillent que dans des dossiers temporaires, et ce répertoire leur est indifférent.
    workingDir = noyau.asFile
    // …d'où ce repère, pour les tests qui relisent les fichiers du projet Windows lui-même.
    systemProperty("tvslim.projet", layout.projectDirectory.asFile.absolutePath)
    // Captures sur un vrai téléviseur, seulement sur demande : -Pmateriel=192.168.2.135
    providers.gradleProperty("materiel").orNull?.let { systemProperty("tvslim.materiel", it) }
    // Planche des logos et des cartes, sans téléviseur, seulement sur demande : -Pplanche=1
    providers.gradleProperty("planche").orNull?.let { systemProperty("tvslim.planche", it) }
    // Relais de mise à jour éprouvé pour de vrai, seulement sur demande : -PrelaisMsi=… -PrelaisExe=…
    providers.gradleProperty("relaisMsi").orNull?.let { systemProperty("tvslim.relais.msi", it) }
    providers.gradleProperty("relaisExe").orNull?.let { systemProperty("tvslim.relais.exe", it) }
    providers.gradleProperty("relaisPid").orNull?.let { systemProperty("tvslim.relais.pid", it) }
    systemProperty("tvslim.captures", layout.buildDirectory.dir("captures").get().asFile.absolutePath)
    useJUnit()
}

compose.resources {
    packageOfResClass = "net.jolabs40.tvslim.windows.ressources"
    publicResClass = false
    generateResClass = always
}

/** jpackage manque au JBR d'Android Studio : l'installateur se fabrique avec le JDK d'Adoptium. */
val jdkPaquetage = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
    vendor = JvmVendorSpec.ADOPTIUM
}

compose.desktop {
    application {
        mainClass = "net.jolabs40.tvslim.windows.MainKt"
        javaHome = jdkPaquetage.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "TV Slim"
            packageVersion = versionApp
            description = "Reversible debloat for Android TV"
            vendor = "jolabs40"
            copyright = "jolabs40"

            // Ceux que suggère `suggestRuntimeModules`, plus ce qu'aucune analyse de dépendances ne
            // voit : Ed25519 et TLS (jdk.crypto.ec), les mois en français (jdk.localedata) et les
            // lecteurs d'écran (jdk.accessibility).
            modules(
                "java.instrument",
                "java.net.http",
                "jdk.unsupported",
                "jdk.crypto.ec",
                "jdk.localedata",
                "jdk.accessibility",
            )

            windows {
                iconFile.set(project.file("packaging/tvslim.ico"))
                // Ne jamais changer : c'est lui qui fait qu'une nouvelle version remplace
                // l'ancienne au lieu de s'installer à côté.
                upgradeUuid = "b2c131f4-d4d2-415b-980e-57b089c14718"
                // Installée dans le profil : ni droits d'administrateur, ni fenêtre UAC, et la
                // mise à jour automatique passe sans rien demander non plus.
                perUserInstall = true
                dirChooser = false
                menu = true
                menuGroup = "TV Slim"
                shortcut = true
            }
        }
    }
}
