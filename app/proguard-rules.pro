# ============================================================================
# OpenCode Android — R8/ProGuard rules (verified via ./gradlew assembleRelease)
# ============================================================================

# ---------- kotlinx.serialization -------------------------------------------
# Annotations and defaults must survive for the generated serializers and for
# polymorphic sealed hierarchies (MessagePart discriminator = "type").
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**

# Keep the generated `$serializer` classes for every app model/DTO so
# ContentNegotiation's runtime-type lookup (guessSerializer) still resolves
# after minification/obfuscation.
-keep,includedescriptorclasses class com.anomalyco.opencode.**$$serializer { *; }

# Official serialization recipe: keep Companion fields of @Serializable
# classes and the `serializer()` accessors on their (possibly named)
# companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}

# Enums serialized by name (@SerialName on ToolStatus/StepState/MessageRole).
-keepclassmembers enum com.anomalyco.opencode.** {
    **[] $VALUES;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- Ktor / OkHttp / transitive ---------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn java.awt.**
-dontwarn javax.annotation.**
-dontwarn io.ktor.utils.io.**

# ---------- androidx.security-crypto (Tink) ----------------------------------
# Tink (backs EncryptedSharedPreferences) references compile-only Google
# error-prone annotations that are never packaged; safe to suppress (R8
# missing_rules.txt).
-dontwarn com.google.errorprone.annotations.**
