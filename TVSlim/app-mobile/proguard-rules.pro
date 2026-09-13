# dadb s'appuie sur Bouncy Castle et Okio, appelés par réflexion pour la crypto ADB.
-keep class dadb.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Modèles sérialisés du noyau partagé.
-keepclasseswithmembers class net.jolabs40.tvslim.catalog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class net.jolabs40.tvslim.journal.** {
    kotlinx.serialization.KSerializer serializer(...);
}
