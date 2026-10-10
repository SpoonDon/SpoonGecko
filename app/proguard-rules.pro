# ============================================================
# SpoonGecko ProGuard rules
# These keep rules are required so that GeckoView and its
# reflection-based internals continue to work after R8 minification.
# ============================================================

# Keep all GeckoView classes and their members — GeckoView uses
# reflection, JNI, and dynamic class loading extensively.
-keep class org.mozilla.geckoview.** { *; }
-keep interface org.mozilla.geckoview.** { *; }
-keep enum org.mozilla.geckoview.** { *; }

# Keep all SpoonGecko app classes so that Android can instantiate
# the Application, Activity, Service, and BroadcastReceiver.
-keep class com.spoongecko.app.** { *; }

# Keep any class that extends Android components that are instantiated
# by the framework via reflection.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# Keep WebExtension support classes.
-keep class org.mozilla.geckoview.WebExtension** { *; }
-keep class org.mozilla.geckoview.WebExtensionController** { *; }

# Keep native method classes and their names.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enum values() and valueOf() methods — used by the framework.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable CREATOR fields.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Silence warnings about optional dependencies.
-dontwarn org.mozilla.geckoview.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Keep annotations used by the framework.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# R8 optimization settings — moderate optimization to keep the build
# reliable while still reducing size.
-optimizationpasses 3
-allowaccessmodification
-repackageclasses ''
