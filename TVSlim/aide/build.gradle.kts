// Le petit programme que TV Slim lance sur l'appareil par `app_process`, comme scrcpy son serveur : il lit le nom et
// l'icône des applications par le PackageManager, ce qu'aucune commande d'ADB ne sait faire. Java pur, sans Kotlin
// ni dépendance : quelques kilo-octets.
//
// Il n'est pas installé : copié dans /data/local/tmp le temps d'une lecture, puis effacé. Sa signature ne compte pas
// — app_process ne la vérifie pas —, d'où l'APK de release non signé.
plugins {
    id("com.android.application")
}

android {
    namespace = "net.jolabs40.tvslim.aide"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.jolabs40.tvslim.aide"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

// Les deux applications lisent l'aide dans les ressources du noyau — la version Windows sans SDK Android. Après
// toute modification de l'aide :  ./gradlew :aide:copierDansLeNoyau
tasks.register<Copy>("copierDansLeNoyau") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/aide-release-unsigned.apk"))
    into(rootProject.file("core/src/main/assets/aide"))
    rename { "tvslim-aide.apk" }
}
