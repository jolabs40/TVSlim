// Helper that TV Slim runs on the device through `app_process`, the way scrcpy runs its server. It reads app names
// and icons from the PackageManager, which no ADB command can do. Plain Java, no Kotlin or dependencies: a few KB.
//
// It is never installed: copied to /data/local/tmp for one read, then deleted. app_process does not check
// signatures, hence the unsigned release APK.
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

// Both apps read the helper from the core module's assets, so the Windows build needs no Android SDK. After any
// change to the helper:  ./gradlew :aide:copierDansLeNoyau
tasks.register<Copy>("copierDansLeNoyau") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/aide-release-unsigned.apk"))
    into(rootProject.file("core/src/main/assets/aide"))
    rename { "tvslim-aide.apk" }
}
