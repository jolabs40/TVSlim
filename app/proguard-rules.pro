# Shizuku : le service utilisateur est instancié par réflexion depuis le process shell.
-keep class net.jolabs40.tvslim.privileged.ShellService { *; }
-keep interface net.jolabs40.tvslim.privileged.IShellService { *; }
-keep class net.jolabs40.tvslim.privileged.IShellService$** { *; }
-keep class rikka.shizuku.** { *; }

# Modèles sérialisés du catalogue.
-keepclassmembers class net.jolabs40.tvslim.catalog.** {
    *** Companion;
}
-keepclasseswithmembers class net.jolabs40.tvslim.catalog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
