# Kotlinx Serialization: keep generated serializers of models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.anomalyco.opencode.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.anomalyco.opencode.**$$serializer { *; }

# Ktor / OkHttp
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
