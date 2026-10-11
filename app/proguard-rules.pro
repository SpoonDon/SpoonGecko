# ============================================================
# SpoonGecko ProGuard rules for GeckoView 156
# ============================================================

# --- Disable obfuscation entirely ---
# GeckoView uses reflection and JNI extensively. Obfuscation
# can silently break the native bridge and cause crashes.
-dontobfuscate

# --- Keep all GeckoView classes ---
-keep class org.mozilla.geckoview.** { *; }
-keep interface org.mozilla.geckoview.** { *; }
-keep enum org.mozilla.geckoview.** { *; }

# --- Keep GeckoView internal utilities (DebugConfig, etc.) ---
-keep class org.mozilla.gecko.** { *; }

# --- Keep your app's classes ---
-keep class com.spoongecko.app.** { *; }

# --- Keep framework components instantiated by Android ---
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# --- Keep native methods and their class names ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Keep enum values() and valueOf() ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Keep Parcelable CREATOR fields ---
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# --- Keep Serializable classes ---
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --- Preserve annotations and signatures ---
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# --- Silence warnings from optional dependencies ---
-dontwarn org.mozilla.geckoview.**
-dontwarn org.mozilla.gecko.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn android.**

# --- Aggressive but safe optimization settings ---
-optimizationpasses 3
-allowaccessmodification
-repackageclasses ''

# --- Remove debug logs from release builds ---
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
