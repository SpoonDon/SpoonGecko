
# Keep GeckoView engine classes intact
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.geckoview.**
-keepclassmembers class * {
    @org.mozilla.geckoview.GeckoView.* <methods>;
}
