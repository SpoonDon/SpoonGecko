# GeckoView ships its own consumer rules, but keep the runtime entry points.
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.**

# Kotlin metadata
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }

# Our Compose + ViewModel entry points
-keep class com.spoongecko.app.SpoonGeckoApp { *; }
-keep class com.spoongecko.app.MainActivity { *; }
-keep class com.spoongecko.app.service.** { *; }
-keep class com.spoongecko.app.receiver.** { *; }
