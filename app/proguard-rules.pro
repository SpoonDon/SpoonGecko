# ---------------------------------------------------------------------------
# SpoonGecko R8 rules
# ---------------------------------------------------------------------------
# GeckoView reaches into Java from C++ via JNI and, in a few places, via
# reflection. R8 cannot see those edges by itself and would otherwise strip
# classes and methods the runtime needs. The rules below are the safety net.
#
# Mozilla also ships consumer rules inside the GeckoView AAR; where those
# overlap with the rules below, R8 simply applies both. No conflict.
# ---------------------------------------------------------------------------

# --- GeckoView / Gecko JNI surface -----------------------------------------
# The entire public API of GeckoView is reachable from native code and must
# survive R8's tree-shaking and renaming.
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.thirdparty.** { *; }

# Methods and fields annotated @WrapForJNI (and its variants) are called
# from C++ by name. R8 renames them by default and the JNI lookup fails
# with an UnsatisfiedLinkError at runtime.
-keepclassmembers class * {
    @org.mozilla.gecko.annotation.WrapForJNI *;
    @org.mozilla.gecko.annotation.WrapForJNI.Override *;
    @org.mozilla.gecko.annotation.RobocopTarget *;
}
-keepclasseswithmembers class * {
    @org.mozilla.gecko.annotation.WrapForJNI *;
    @org.mozilla.gecko.annotation.WrapForJNI.Override *;
    @org.mozilla.gecko.annotation.RobocopTarget *;
}
-keep class org.mozilla.gecko.annotation.** { *; }

# --- Our own JS bridge surface ----------------------------------------------
# Methods annotated @JavascriptInterface are invoked from page JavaScript
# by name. Renaming them silently breaks the bridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Standard Android constructs R8 sometimes mishandles --------------------
# Native methods — the JVM matches them by name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Custom View constructors — called reflectively by LayoutInflater.
-keepclasseswithmembers class * {
    public <init>(android.content.Context);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Parcelable CREATOR fields.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Serializable contract.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Enum valueOf / values — normally handled by the default ProGuard config,
# but making it explicit avoids surprises with GeckoView's many enums.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Warnings ----------------------------------------------------------------
# The GeckoView AAR references tooling-only classes we don't use. Silence
# them so R8 doesn't fail the build on missing references.
-dontwarn org.mozilla.geckoview.**
-dontwarn org.mozilla.gecko.**
-dontwarn org.mozilla.thirdparty.**
