package com.vlade.library;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The phone half of the music library.
 *
 * A container, not the app: everything you see comes from the page served at
 * home, so a change to the player arrives without an install. The container
 * does what Android alone can do - keep playing with the screen off, put the
 * track on a notification, remember the address, notice a new build of itself -
 * and, since a black screen tells nobody anything, it reports every step of a
 * load on screen instead of failing silently.
 */
public class MainActivity extends Activity {

    static final String PREFS = "cfg";
    static final String KEY_URL = "baseUrl";
    static final String KEY_UPDATE = "updateUrl";
    static final String DEFAULT_URL = "http://192.168.0.12:8787";
    static final int LOAD_TIMEOUT_MS = 8000;

    WebView web;
    LinearLayout root;
    FrameLayout stage;
    TextView banner;
    TextView status;
    String base;
    Updater.Rel pendingUpdate;
    String lastError = "";
    long loadStarted = 0;
    boolean pageFinished = false;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashLog();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        base = p.getString(KEY_URL, DEFAULT_URL);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0b0b0d"));

        banner = new TextView(this);
        banner.setBackgroundColor(Color.parseColor("#221a3d"));
        banner.setTextColor(Color.parseColor("#c9b9ff"));
        banner.setTextSize(14);
        banner.setPadding(dp(18), dp(12), dp(18), dp(12));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> install(pendingUpdate));
        root.addView(banner);

        status = new TextView(this);
        status.setBackgroundColor(Color.parseColor("#1a1a20"));
        status.setTextColor(Color.parseColor("#b9b9c6"));
        status.setTextSize(12);
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        status.setText("Starting\u2026");
        root.addView(status);

        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        PlaybackService.onToggle = () -> runJs("if(window.playerToggle)window.playerToggle()");
        PlaybackService.onNext = () -> runJs("if(window.playerNext)window.playerNext()");

        askNotifications();
        load();
    }

    // ---- the page -----------------------------------------------------------

    void load() {
        ui.removeCallbacksAndMessages(null);
        stage.removeAllViews();
        pageFinished = false;
        lastError = "";
        loadStarted = System.currentTimeMillis();
        say("Connecting to " + base + "\u2026");

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " LibraryShell/" + versionName());
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(Color.parseColor("#0b0b0d"));
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u.getHost() != null && u.getHost().equals(Uri.parse(base).getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                lastError = "network: " + e.getDescription() + " (" + e.getErrorCode() + ")";
                showUnreachable(lastError);
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                if (!r.isForMainFrame()) return;
                lastError = "HTTP " + e.getStatusCode() + " from " + r.getUrl().getPath();
                showUnreachable(lastError);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageFinished = true;
                long ms = System.currentTimeMillis() - loadStarted;
                say("Loaded in " + ms + " ms");
                v.evaluateJavascript("document.documentElement.classList.add('in-app')", null);
                // A page that loads but shows nothing is its own failure mode; say so
                // instead of leaving a black rectangle.
                v.evaluateJavascript("(document.querySelectorAll('.row')||[]).length", value -> {
                    int rows = 0;
                    try { rows = Integer.parseInt(value.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                    if (rows == 0) say("Page loaded but has no tracks - is " + base + " the music server?");
                });
            }
        });
        stage.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(base);
        addMenuButton();

        ui.postDelayed(() -> {
            if (!pageFinished) {
                lastError = "no answer in " + (LOAD_TIMEOUT_MS / 1000) + " s";
                showUnreachable(lastError);
            }
        }, LOAD_TIMEOUT_MS);

        checkUpdates(false);
    }

    /** A crash on the phone is unreadable without adb; put it in a file the app can show. */
    void installCrashLog() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.File f = new java.io.File(getExternalFilesDir(null), "crash.txt");
                java.io.PrintWriter w = new java.io.PrintWriter(f);
                w.println(new java.util.Date().toString());
                e.printStackTrace(w);
                w.close();
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    String crashTail() {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "crash.txt");
            if (!f.exists()) return "";
            String all = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            String[] lines = all.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, lines.length); i++) sb.append(lines[i]).append("\n");
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    void say(String s) {
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    void showUnreachable(String reason) {
        ui.removeCallbacksAndMessages(null);
        stage.removeAllViews();
        say("Not connected");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(26), dp(20), dp(26), dp(20));

        TextView t = new TextView(this);
        t.setText("Can't reach the library");
        t.setTextColor(Color.parseColor("#f4f2ef"));
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView d = new TextView(this);
        d.setText(base + "\n\n" + reason
                + "\n\nThe machine has to be awake and on the same Wi-Fi. If its address changed, set it again.");
        d.setTextColor(Color.parseColor("#9a9aa4"));
        d.setTextSize(14);
        d.setGravity(Gravity.CENTER);
        d.setPadding(0, dp(12), 0, dp(20));
        box.addView(d);

        box.addView(button("Try again", v -> load()));
        box.addView(button("Test connection", v -> testConnection()));
        box.addView(button("Change address", v -> askAddress()));

        stage.addView(box);
        addMenuButton();
    }

    Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        return b;
    }

    /** Tells apart "no route to the machine" from "the machine answered but not with the app". */
    void testConnection() {
        say("Testing " + base + "\u2026");
        final String url = base + "/api/health";
        new Thread(() -> {
            String result;
            HttpURLConnection c = null;
            try {
                long t0 = System.currentTimeMillis();
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                int code = c.getResponseCode();
                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[2048];
                    int n = in.read(buf);
                    if (n > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                }
                result = "HTTP " + code + " in " + (System.currentTimeMillis() - t0) + " ms\n\n" + sb;
            } catch (Exception e) {
                result = e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\n\nNothing answered. The usual causes: the machine asleep, a VPN on it, "
                        + "or this phone on mobile data instead of the home Wi-Fi.";
            } finally {
                if (c != null) c.disconnect();
            }
            final String shown = result;
            runOnUiThread(() -> {
                say("Test done");
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Connection test")
                        .setMessage(url + "\n\n" + shown)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }).start();
    }

    void askAddress() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(base);
        new AlertDialog.Builder(this)
                .setTitle("Server address")
                .setMessage("Where the library lives, e.g. http://192.168.0.12:8787")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    String v = e.getText().toString().trim();
                    if (v.isEmpty()) return;
                    if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://" + v;
                    base = v.replaceAll("/+$", "");
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, base).apply();
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- the little menu ----------------------------------------------------

    void addMenuButton() {
        TextView m = new TextView(this);
        m.setText("\u22ee");
        m.setTextSize(20);
        m.setTextColor(Color.parseColor("#9a9aa4"));
        m.setGravity(Gravity.CENTER);
        m.setAlpha(0.5f);
        m.setOnClickListener(v -> showMenu());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44));
        lp.gravity = Gravity.TOP | Gravity.END;
        stage.addView(m, lp);
    }

    void showMenu() {
        String[] items = { "Reload", "Server address", "Test connection", "Check for updates", "Diagnostics" };
        new AlertDialog.Builder(this)
                .setItems(items, (d, i) -> {
                    if (i == 0) load();
                    else if (i == 1) askAddress();
                    else if (i == 2) testConnection();
                    else if (i == 3) checkUpdates(true);
                    else diagnostics();
                })
                .show();
    }

    void diagnostics() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String upd = p.getString(KEY_UPDATE, getString(R.string.update_url));
        String msg = "Container   " + versionName() + " (code " + versionCode() + ")\n"
                + "Android     " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n"
                + "Device      " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Server      " + base + "\n"
                + "Last load   " + (lastError.isEmpty() ? "ok" : lastError) + "\n"
                + "Update from " + (upd == null || upd.isEmpty() ? "not set" : upd)
                + (crashTail().isEmpty() ? "" : "\n\nLast crash:\n" + crashTail());
        new AlertDialog.Builder(this)
                .setTitle("Diagnostics")
                .setMessage(msg)
                .setPositiveButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", msg));
                    toast("Copied");
                })
                .setNegativeButton("Close", null)
                .setNeutralButton("Update source", (d, w) -> askUpdateUrl())
                .show();
    }

    void askUpdateUrl() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_UPDATE, getString(R.string.update_url)));
        new AlertDialog.Builder(this)
                .setTitle("Update source")
                .setMessage("URL of latest.json in the repository, e.g.\nhttps://raw.githubusercontent.com/USER/REPO/main/latest.json")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putString(KEY_UPDATE, e.getText().toString().trim()).apply())
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- updates ------------------------------------------------------------

    void checkUpdates(boolean loud) {
        String url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_UPDATE, getString(R.string.update_url));
        if (url == null || url.isEmpty()) {
            if (loud) toast("No update source set - Diagnostics > Update source.");
            return;
        }
        if (loud) toast("Checking\u2026");
        Updater.check(url, (rel, error) -> runOnUiThread(() -> {
            if (error != null) { if (loud) toast("Check failed: " + error); return; }
            if (rel == null) { if (loud) toast("No version published yet."); return; }
            if (rel.code <= versionCode()) { if (loud) toast("Newest build installed (" + versionName() + ")."); return; }
            pendingUpdate = rel;
            banner.setText("Update " + rel.name + " is ready - tap to install");
            banner.setVisibility(View.VISIBLE);
            if (loud) toast("Update " + rel.name + " available");
        }));
    }

    void install(Updater.Rel rel) {
        if (rel == null) return;
        banner.setText("Downloading " + rel.name + "\u2026");
        Updater.download(this, rel, (apk, error) -> runOnUiThread(() -> {
            if (error != null) { banner.setText("Download failed: " + error); return; }
            banner.setVisibility(View.GONE);
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(apk, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast("Can't open the installer: " + e.getMessage());
            }
        }));
    }

    // ---- plumbing -----------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        checkUpdates(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacksAndMessages(null);
    }

    void runJs(String js) {
        if (web != null) runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        moveTaskToBack(true);
    }

    void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 7); } catch (Exception ignored) {}
        }
    }

    int versionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /** What the page is allowed to call. */
    class Bridge {
        @JavascriptInterface
        public void nowPlaying(String title, String artist, boolean playing) {
            runOnUiThread(() -> {
                if (title == null || title.isEmpty()) { PlaybackService.stop(MainActivity.this); return; }
                PlaybackService.show(MainActivity.this, title, artist, playing);
            });
        }

        @JavascriptInterface
        public void settings() { runOnUiThread(MainActivity.this::showMenu); }

        @JavascriptInterface
        public void checkForUpdate() { runOnUiThread(() -> checkUpdates(true)); }

        @JavascriptInterface
        public String shellVersion() { return versionName(); }

        @JavascriptInterface
        public String serverUrl() { return base; }
    }
}
