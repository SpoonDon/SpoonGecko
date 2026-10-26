# Keep all GeckoView classes because they are called from JNI,
# reflection, and the built-in WebExtension runtime.
-keep class org.mozilla.geckoview.** { *; }
-keepclassmembers class org.mozilla.geckoview.** { *; }
-keep interface org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**

# Keep any class that declares a native method (required by JNI).
-keepclasseswithmembernames class * {
    native <methods>;
}
