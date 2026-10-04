# GeckoView is heavily JNI/reflection driven. Don't let R8 touch it.
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.**
-dontwarn org.json.**
