# ------------------------------------------------------------
# SpoonGecko + GeckoView
# ------------------------------------------------------------
# GeckoView 158 ships its own consumer rules (JNI surface, annotations),
# but these duplicate the critical parts so the build can never break
# if the artifact changes.
-keep class org.mozilla.geckoview.** { *; }
-keepclassmembers class org.mozilla.geckoview.** { *; }
-keep interface org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# org.mozilla.gecko.* classes are reached from native code (libxul) by name.
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.gecko.**

# Keep any class that declares a native method (required by JNI).
-keepclasseswithmembernames class * {
    native <methods>;
}

# ------------------------------------------------------------
# WorkManager instantiates Worker subclasses reflectively by class name.
# (work-runtime ships equivalent consumer rules; belt and braces.)
# ------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}
-keep class com.spoongecko.browser.ExtensionUpdateWorker { *; }
-dontwarn androidx.work.**

# ------------------------------------------------------------
# SnakeYAML (transitive dependency of GeckoView)
# ------------------------------------------------------------
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# java.beans is not available on Android but SnakeYAML references it.
-dontwarn java.beans.**

# Kotlin metadata warnings from the GeckoView artifact — safe to ignore.
-dontwarn kotlin.**
-dontwarn kotlinx.**
