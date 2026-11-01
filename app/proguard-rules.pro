# CatMusic release rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.**
-keep class com.catmusic.app.data.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }

# Coil
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Local Sonic Topography lyric stage bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.catmusic.app.ui.screens.SonicLyricsBridge { *; }
-keep class com.catmusic.app.online.MusicBridge { *; }
-keep class com.catmusic.app.online.MusicApi { *; }
