# 1. Native piano audio engine JNI (matches Java_cn_musicpilgrimage_app_performance_NativePiano_* in PianoEngine.cpp)
-keep class cn.musicpilgrimage.app.performance.NativePiano { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 2. MapLibre Native Android SDK (supplementing AAR consumer rules)
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# 3. Supabase Auth + kotlinx.serialization (used by UserSession in SecureSettings.kt)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep class io.github.jan.supabase.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class cn.musicpilgrimage.app.**$$serializer { *; }
-keepclassmembers class * {
    public static final *** Companion;
}

# 4. AndroidSVG / Ktor / OkHttp / Room rules
-keep class com.caverock.androidsvg.** { *; }
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
