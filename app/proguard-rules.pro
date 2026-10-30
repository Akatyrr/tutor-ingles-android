# A página chama a ponte AndroidVoz pelo nome dos métodos: o R8 não pode apagar nem renomear esses métodos
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
