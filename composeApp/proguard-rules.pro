# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.termish.**$$serializer { *; }
-keepclassmembers class dev.termish.** { *** Companion; }
-keepclasseswithmembers class dev.termish.** { kotlinx.serialization.KSerializer serializer(...); }

# coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Compose
-dontwarn androidx.compose.**

# sshj / BouncyCastle（JVM SSH 引擎）
-dontwarn org.bouncycastle.**
-dontwarn com.hierynomus.**
-dontwarn net.schmizz.**
-dontwarn org.slf4j.**
-keep class org.bouncycastle.** { *; }
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.slf4j.** { *; }
