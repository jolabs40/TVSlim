pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
plugins {
    // Fournit à la demande le JDK 21 d'Adoptium : c'est lui qui porte jpackage, absent du JBR
    // d'Android Studio, et donc lui qui fabrique l'installateur.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "TVSlimWindows"
