# Karoo extension entry point — referenced by manifest, must not be stripped
-keep class com.hainesy.karoogarage.GarageExtension { *; }

# androidx.security:security-crypto pulls Tink, which references these
# JSR-305 annotations that aren't shipped on Android. Safe to ignore.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.hainesy.karoogarage.**$$serializer { *; }
-keepclassmembers class com.hainesy.karoogarage.** {
    *** Companion;
}
-keepclasseswithmembers class com.hainesy.karoogarage.** {
    kotlinx.serialization.KSerializer serializer(...);
}

