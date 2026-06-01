# Naviglink driver — Proguard rules
#
# Pro MVP držíme minifikaci vypnutou (build.gradle: isMinifyEnabled = false).
# Pokud později zapnete, tyto rules zachovají reflection-závislé třídy.

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Ktor
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }

# Bouncy Castle Ed25519
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
