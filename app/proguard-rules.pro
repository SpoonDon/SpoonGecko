# ------------------------------------------------------------
# GeckoView
# ------------------------------------------------------------
-keep class org.mozilla.geckoview.** { *; }
-keepclassmembers class org.mozilla.geckoview.** { *; }
-keep interface org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# Keep any class that declares a native method (required by JNI).
-keepclasseswithmembernames class * {
    native <methods>;
}

# ------------------------------------------------------------
# SnakeYAML (transitive dependency of GeckoView)
# ------------------------------------------------------------
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# ------------------------------------------------------------
# java.beans (not available on Android, referenced by SnakeYAML)
# ------------------------------------------------------------
-keep class java.beans.** { *; }
-dontwarn java.beans.**
