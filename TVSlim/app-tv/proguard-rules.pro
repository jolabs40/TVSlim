# Serialized catalogue and journal models (:core module).
-keepclassmembers class net.jolabs40.tvslim.catalog.** {
    *** Companion;
}
-keepclasseswithmembers class net.jolabs40.tvslim.catalog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class net.jolabs40.tvslim.journal.** {
    *** Companion;
}
-keepclasseswithmembers class net.jolabs40.tvslim.journal.** {
    kotlinx.serialization.KSerializer serializer(...);
}
