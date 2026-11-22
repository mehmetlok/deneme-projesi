package com.mehmet.hizlioku;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/assets/index.html";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36";
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0E0F12);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true; // dış linkler tarayıcıda açılır
            }
        });

        String[] sh = shareData(getIntent());
        String start = BASE;
        if (sh != null) start = BASE + "?text=" + Uri.encode(sh[0]) + "&title=" + Uri.encode(sh[1]);
        web.loadUrl(start);
    }

    /** Paylaş menüsünden gelen metin/link. */
    private String[] shareData(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return null;
        CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (t == null) return null;
        String sub = i.getStringExtra(Intent.EXTRA_SUBJECT);
        return new String[]{t.toString(), sub == null ? "" : sub};
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String[] sh = shareData(intent);
        if (sh != null && web != null) {
            web.evaluateJavascript("window.HO && HO.share(" + JSONObject.quote(sh[0]) + "," + JSONObject.quote(sh[1]) + ")", null);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private void js(final String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    /** JavaScript'ten çağrılan yerel fonksiyonlar. */
    class Bridge {
        @JavascriptInterface
        public void fetchUrl(final String id, final String url) {
            pool.execute(() -> {
                try {
                    String[] r = httpGet(url);
                    js("window.__nfCb(" + JSONObject.quote(id) + ",true," + r[0] + "," + JSONObject.quote(r[1]) + ","
                            + JSONObject.quote(r[2]) + "," + JSONObject.quote(r[3]) + ")");
                } catch (Throwable e) {
                    String msg = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                    js("window.__nfCb(" + JSONObject.quote(id) + ",false,0,'',''," + JSONObject.quote(msg) + ")");
                }
            });
        }

        @JavascriptInterface
        public void keepScreenOn(final boolean on) {
            ui.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public String clipboard() {
            final String[] out = {""};
            final CountDownLatch latch = new CountDownLatch(1);
            ui.post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip()) {
                        ClipData cd = cm.getPrimaryClip();
                        if (cd != null && cd.getItemCount() > 0) {
                            CharSequence t = cd.getItemAt(0).coerceToText(MainActivity.this);
                            if (t != null) out[0] = t.toString();
                        }
                    }
                } catch (Exception ignored) { }
                latch.countDown();
            });
            try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return out[0];
        }
    }

    /** Yönlendirmeleri izleyerek sayfayı indirir: {status, contentType, finalUrl, body}. */
    private String[] httpGet(String u) throws Exception {
        URL url = new URL(u);
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("Yönlendirme adresi yok");
                url = new URL(url, loc);
                continue;
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in);
            String ct = c.getContentType();
            if (ct == null) ct = "";
            String body = new String(data, charsetOf(ct, data));
            c.disconnect();
            return new String[]{String.valueOf(code), ct, url.toString(), body};
        }
        throw new Exception("Çok fazla yönlendirme");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        try {
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
                if (total > MAX_BYTES) break;
            }
        } finally { in.close(); }
        return bo.toByteArray();
    }

    private static final Pattern CT_CS = Pattern.compile("charset=([\\w\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CS = Pattern.compile("<meta[^>]+charset=[\"']?([\\w\\-]+)", Pattern.CASE_INSENSITIVE);

    private static Charset charsetOf(String ct, byte[] data) {
        String name = null;
        Matcher m = CT_CS.matcher(ct);
        if (m.find()) name = m.group(1);
        if (name == null) {
            String head = new String(data, 0, Math.min(data.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher mm = META_CS.matcher(head);
            if (mm.find()) name = mm.group(1);
        }
        try { if (name != null && Charset.isSupported(name)) return Charset.forName(name); } catch (Exception ignored) { }
        return StandardCharsets.UTF_8;
    }
}
