# Los metodos expuestos al JavaScript no se pueden renombrar.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.dantsu.escposprinter.** { *; }
