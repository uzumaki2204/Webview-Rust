-keep class com.example.webviewbrowser.RustBridge { *; }
-keep class com.example.webviewbrowser.WebTabView$JsBridgeInterface { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
