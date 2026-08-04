# Keep Saatiril app classes
-keep class com.saatiril.fullsystem.** { *; }

# WebView JavaScript Interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView
-keepclassmembers class * extends android.webkit.WebViewClient {
    <methods>;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    <methods>;
}

# Suppress warnings for OkHttp (used by WebView internally)
-dontwarn okhttp3.**
-dontwarn okio.**
