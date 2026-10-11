# ============================================================
# SpoonGecko ProGuard rules for GeckoView
# ============================================================

# Keep all GeckoView classes and members — it uses reflection and JNI extensively.
-keep class org.mozilla.geckoview.** { *; }
-keep interface org.mozilla.geckoview.** { *; }
-keep enum org.mozilla.geckoview.** { *; }

# Keep your app's classes.
-keep class com.spoongecko.app.** { *; }

# Keep framework components instantiated by the Android framework.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep native method names and their classes.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enums, which are accessed via introspection.
-keepclassmembers enum * {
    *;
}

# Keep Parcelable CREATOR fields.
-keepclassmembers class * implements android.os.Parcelable {
    static android.os.Parcelable$Creator CREATOR;
}

# Preserve annotations and signatures.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# Silence warnings from optional dependencies.
-dontwarn org.mozilla.geckoview.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
-dontwarn kotlinx.**

# R8 optimization settings to reduce size.
-optimizationpasses 3
-allowaccessmodification
-repackageclasses ''
