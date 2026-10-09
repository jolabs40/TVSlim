# dadb relies on Bouncy Castle and Okio, called through reflection for ADB crypto.
-keep class dadb.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Serialized models of the shared core.
-keepclasseswithmembers class net.jolabs40.tvslim.catalog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class net.jolabs40.tvslim.journal.** {
    kotlinx.serialization.KSerializer serializer(...);
}
