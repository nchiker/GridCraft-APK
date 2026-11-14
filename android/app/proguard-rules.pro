# The page calls these through addJavascriptInterface.
-keepclassmembers class app.gridcraft.viewer.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
