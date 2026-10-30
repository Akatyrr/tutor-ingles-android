package app.tutoringles;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tutor de Inglês: abre a página do Tutor em tela cheia. O endereço vem do build (tutorUrl em gradle.properties):
 * o servidor próprio (versão independente do Claude, com login por apelido) ou o artifact no claude.ai.
 * O login fica guardado no próprio app; links de fora (YouTube, Spotify)
 * abrem nos apps deles; o seletor de arquivos serve para escolher fotos.
 * A WebView não tem síntese de voz, então o app entrega a voz do Android à página
 * como window.AndroidVoz (a Emma fala por ela, com voz feminina em inglês).
 * O mesmo objeto também ouve: o reconhecimento de voz do Android transforma a fala do aluno
 * em texto, e a página busca o resultado (ouvido()) para a ligação com a Emma ser falada de verdade.
 */
public class MainActivity extends Activity {

    private static final String TUTOR_URL = BuildConfig.TUTOR_URL;
    private static final int FILE_REQUEST = 1;
    private static final int MIC_REQUEST = 2;

    // Códigos das vozes femininas do Google em inglês (os nomes não dizem o gênero)
    // Vozes femininas do Google: americanas (sfg, tpf, iob, iog, tpc) e britânicas (gba, gbc, gbg)
    private static final Pattern FEMALE_VOICE = Pattern.compile("x-(sfg|tpf|iob|iog|tpc|gba|gbc|gbg)|female", Pattern.CASE_INSENSITIVE);

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;

    // Reconhecimento de voz: a página pede ouvir(), e consulta ouvido() até vir "pronto", "silencio" ou "erro"
    private SpeechRecognizer reconhecedor;
    private volatile String ouvirEstado = "parado";
    private volatile String ouvirTexto = "";
    private volatile String ouvirParcial = "";
    private volatile int ouvirErro = 0;
    private String idiomaPendente = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setLanguage(Locale.US);
            Voice v = melhorVoz("US");
            if (v != null) tts.setVoice(v);
            ttsReady = true;
        });
        web.addJavascriptInterface(new Voz(), "AndroidVoz");

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // links de "nova aba" (YouTube, Spotify) chegam em onCreateWindow, que manda para fora
        s.setSupportMultipleWindows(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isClaude(uri)) return false;
                openOutside(uri);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
            }

            // Link de "nova aba": uma WebView descartável recebe o endereço, que vai para o app certo (YouTube, Spotify, navegador)
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView aba = new WebView(MainActivity.this);
                aba.setWebViewClient(new WebViewClient() {
                    private boolean aberto = false;
                    private void abre(WebView v, Uri uri) {
                        if (aberto || uri == null || "about".equals(uri.getScheme())) return;
                        aberto = true;
                        openOutside(uri);
                        v.post(v::destroy);
                    }
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        abre(v, request.getUrl());
                        return true;
                    }
                    @Override
                    public void onPageStarted(WebView v, String url, Bitmap favicon) {
                        abre(v, url == null ? null : Uri.parse(url));
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(aba);
                resultMsg.sendToTarget();
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(TUTOR_URL);
    }

    // Páginas do próprio Tutor (servidor) e do Claude ficam dentro do app; o resto vai para o app certo
    private static boolean isClaude(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || !scheme.startsWith("http")) return false;
        host = host.toLowerCase();
        String tutorHost = Uri.parse(TUTOR_URL).getHost();
        if (tutorHost != null && host.equals(tutorHost.toLowerCase())) return true;
        return host.equals("claude.ai") || host.endsWith(".claude.ai")
            || host.equals("claude.com") || host.endsWith(".claude.com")
            || host.endsWith("anthropic.com") || host.endsWith("claudeusercontent.com")
            || host.equals("accounts.google.com");
    }

    // Melhor voz em inglês para um país ("US" para a Emma, "GB" para a Ana): o sotaque certo pesa mais,
    // depois voz feminina, depois a qualidade (as de alta qualidade soam bem mais naturais). Voz não baixada fica de fora.
    private final java.util.Map<String, Voice> vozes = new java.util.HashMap<>();
    private Voice melhorVoz(String pais) {
        if (vozes.containsKey(pais)) return vozes.get(pais);
        Voice best = null;
        int melhor = Integer.MIN_VALUE;
        try {
            for (Voice v : tts.getVoices()) {
                Locale l = v.getLocale();
                if (l == null || !"en".equals(l.getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                int nota = (pais.equals(l.getCountry()) ? 2000 : 0)
                    + (FEMALE_VOICE.matcher(v.getName()).find() ? 1000 : 0)
                    + v.getQuality()
                    - v.getLatency() / 10;
                if (nota > melhor) { melhor = nota; best = v; }
            }
        } catch (Exception e) {
            // sem lista de vozes: fica a voz padrão em inglês
        }
        vozes.put(pais, best);
        return best;
    }

    /** Voz do Android para a página (chamada pelo JavaScript do Tutor). */
    private class Voz {
        @JavascriptInterface
        public boolean pronta() { return ttsReady; }

        @JavascriptInterface
        public void falar(String texto, float velocidade) {
            if (!ttsReady || texto == null) return;
            tts.setSpeechRate(Math.max(0.5f, Math.min(1.5f, velocidade)));
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "tutor");
        }

        /** Fala com a voz de um país: "US" (Emma) ou "GB" (Ana, britânica). */
        @JavascriptInterface
        public void falarComo(String texto, float velocidade, String pais) {
            if (!ttsReady || texto == null) return;
            String p = "GB".equals(pais) ? "GB" : "US";
            Voice v = melhorVoz(p);
            if (v != null) tts.setVoice(v); else tts.setLanguage("GB".equals(p) ? Locale.UK : Locale.US);
            falar(texto, velocidade);
        }

        @JavascriptInterface
        public void parar() { if (ttsReady) tts.stop(); }

        @JavascriptInterface
        public boolean falando() { return ttsReady && tts.isSpeaking(); }

        /** O aparelho tem reconhecimento de voz (quase todo Android com o app Google tem). */
        @JavascriptInterface
        public boolean podeOuvir() { return SpeechRecognizer.isRecognitionAvailable(MainActivity.this); }

        /** Começa a ouvir no idioma pedido (ex.: "en-US"); pede o microfone na primeira vez. */
        @JavascriptInterface
        public void ouvir(String idioma) {
            final String lang = idioma == null || idioma.isEmpty() ? "en-US" : idioma;
            ouvirTexto = ""; ouvirParcial = ""; ouvirErro = 0; ouvirEstado = "ouvindo";
            runOnUiThread(() -> pedirMicEOuvir(lang));
        }

        /** Para de ouvir e entrega o que já foi dito. */
        @JavascriptInterface
        public void pararDeOuvir() { runOnUiThread(() -> { if (reconhecedor != null) reconhecedor.stopListening(); }); }

        /** Cancela sem entregar nada (desligou a ligação, trocou de tela). */
        @JavascriptInterface
        public void cancelarOuvir() {
            ouvirEstado = "parado";
            runOnUiThread(() -> { if (reconhecedor != null) reconhecedor.cancel(); });
        }

        /** Estado do reconhecimento: {estado: parado|ouvindo|pronto|silencio|negado|erro, texto, parcial, erro}. */
        @JavascriptInterface
        public String ouvido() {
            try {
                return new JSONObject().put("estado", ouvirEstado).put("texto", ouvirTexto).put("parcial", ouvirParcial).put("erro", ouvirErro).toString();
            } catch (Exception e) {
                return "{\"estado\":\"erro\"}";
            }
        }
    }

    private void pedirMicEOuvir(String lang) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            idiomaPendente = lang;
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, MIC_REQUEST);
            return;
        }
        comecarAOuvir(lang);
    }

    private void comecarAOuvir(String lang) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { ouvirEstado = "erro"; ouvirErro = -1; return; }
        if (reconhecedor == null) {
            reconhecedor = SpeechRecognizer.createSpeechRecognizer(this);
            reconhecedor.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onEvent(int eventType, Bundle params) {}
                @Override public void onPartialResults(Bundle parciais) {
                    ArrayList<String> r = parciais.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty()) ouvirParcial = r.get(0);
                }
                @Override public void onResults(Bundle resultados) {
                    if (!"ouvindo".equals(ouvirEstado)) return;
                    ArrayList<String> r = resultados.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    ouvirTexto = r != null && !r.isEmpty() ? r.get(0) : "";
                    ouvirEstado = ouvirTexto.isEmpty() ? "silencio" : "pronto";
                }
                @Override public void onError(int erro) {
                    if (!"ouvindo".equals(ouvirEstado)) return;
                    ouvirErro = erro;
                    // sem fala ou nada entendido não é falha: a página só pede para falar de novo
                    ouvirEstado = erro == SpeechRecognizer.ERROR_NO_MATCH || erro == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "silencio"
                        : erro == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "negado" : "erro";
                }
            });
        }
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        ouvirEstado = "ouvindo";
        reconhecedor.startListening(it);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == MIC_REQUEST) {
            String lang = idiomaPendente; idiomaPendente = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && lang != null) comecarAOuvir(lang);
            else ouvirEstado = "negado";
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, results);
    }

    private void openOutside(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            // sem app para abrir esse link: fica onde está
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (reconhecedor != null) reconhecedor.destroy();
        web.destroy();
        super.onDestroy();
    }
}
