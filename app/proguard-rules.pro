# WebView invokes these streaming bridge methods by their JavaScript names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
