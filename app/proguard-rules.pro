# The JavaScript bridges are called by name from the WebView
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# Release builds don't log: verbose/debug/info/warning calls are removed (errors stay)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
