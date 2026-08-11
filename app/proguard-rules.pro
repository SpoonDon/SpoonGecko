# Keep GeckoView classes
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Keep JavaScript interface methods (if you use addJavascriptInterface)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
