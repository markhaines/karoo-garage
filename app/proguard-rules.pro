# Karoo extension entry point — referenced by manifest, must not be stripped
-keep class com.hainesy.karoogarage.GarageExtension { *; }

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

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
