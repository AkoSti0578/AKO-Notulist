# kotlinx.serialization: keep generated serializers for our DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class nl.arnoldkolkman.akonotulist.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class nl.arnoldkolkman.akonotulist.**$$serializer { *; }

# Car App Library instantiates the service/screens by name from the manifest.
-keep class nl.arnoldkolkman.akonotulist.car.** { *; }

# OkHttp optional platform integrations.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
