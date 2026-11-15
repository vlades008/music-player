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

import org.json.JSONArray;

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
    String token = "";
    long loadStarted = 0;
    boolean pageFinished = false;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashLog();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        base = p.getString(KEY_URL, DEFAULT_URL);
        token = p.getString("token", "");

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
        try { connect(); } catch (Throwable e) { showFatal(e); }
    }

    /** Anything that throws before the page exists becomes readable text, not black. */
    void showFatal(Throwable e) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            String trace = sw.toString();
            say("Startup failed: " + e);
            stage.removeAllViews();
            android.widget.ScrollView sc = new android.widget.ScrollView(this);
            TextView t = new TextView(this);
            t.setText("The app failed to start.\n\n" + (trace.length() > 4000 ? trace.substring(0, 4000) : trace));
            t.setTextColor(Color.parseColor("#ff9a9a"));
            t.setTextSize(12);
            t.setPadding(dp(16), dp(16), dp(16), dp(16));
            sc.addView(t);
            stage.addView(sc);
            addMenuButton();
        } catch (Throwable ignored) {}
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
                // instead of leaving a black rectangle. A page that is not ours at all
                // is usually a Wi-Fi login (captive portal) - name it.
                v.evaluateJavascript("JSON.stringify({rows:(document.querySelectorAll('.row')||[]).length,title:document.title||'',host:location.host,proto:location.protocol})", raw -> {
                    String s = raw == null ? "" : raw.replace("\\\"", "\"").replaceAll("^\"|\"$", "");
                    int rows = 0;
                    String title = "";
                    String host = "";
                    String proto = "";
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(s);
                        rows = o.optInt("rows", 0);
                        title = o.optString("title", "");
                        host = o.optString("host", "");
                        proto = o.optString("proto", "");
                    } catch (Exception ignored) {}
                    if (rows == 0) {
                        if (!proto.startsWith("http")) {
                            say("The page never arrived (" + lastError + ") - nothing was served at " + base);
                        } else if (!title.toLowerCase().contains("library")) {
                            say("Something answered, but it is not the library (page: \"" + title + "\", host: " + host + ") - usually a Wi-Fi login page.");
                        } else {
                            say("Page loaded but has no tracks - is " + base + " the music server?");
                        }
                    }
                });
            }
        });
        stage.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(base, authHeader());
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

        box.addView(button("Try again", v -> connect()));
        box.addView(button("Test connection", v -> testConnection()));
        box.addView(button("Servers\u2026", v -> askServers()));

        stage.addView(box);
        addMenuButton();
    }

    Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        return b;
    }

    // ---- server addresses ---------------------------------------------------
    // One list, tried in order. At home the LAN address answers first; on mobile
    // data the VPN address does. The first one that answers /api/health wins and
    // becomes the current base, so nothing has to be switched by hand.

    java.util.List<String> servers() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("servers", "");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String u = a.optString(i, "").trim();
                if (!u.isEmpty()) out.add(u);
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) out.add(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, DEFAULT_URL));
        return out;
    }

    void saveServers(java.util.List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) if (s != null && !s.trim().isEmpty()) a.put(takeToken(s.trim().replaceAll("/+$", "")));
        String json = a.toString();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("servers", json)
                .putString(KEY_URL, list.isEmpty() ? DEFAULT_URL : takeToken(list.get(0).trim()))
                .apply();
        base = list.isEmpty() ? DEFAULT_URL : takeToken(list.get(0).trim());
    }

    /** "https://host/?t=SECRET" keeps the secret out of the visible address. */
    String takeToken(String url) {
        int q = url.indexOf("?t=");
        if (q < 0) return url;
        String t = url.substring(q + 3);
        int amp = t.indexOf('&');
        if (amp >= 0) t = t.substring(0, amp);
        token = t;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("token", token).apply();
        return url.substring(0, q);
    }

    java.util.Map<String, String> authHeader() {
        if (token == null || token.isEmpty()) return null;
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("X-Library-Token", token);
        return h;
    }

    /** Probes every address in order and loads the first one that answers as the library. */
    void connect() {
        final java.util.List<String> list = servers();
        say("Connecting to " + list.get(0) + "\u2026");
        new Thread(() -> {
            String winner = null;
            StringBuilder log = new StringBuilder();
            for (String u : list) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(u + "/api/health").openConnection();
                    c.setConnectTimeout(1600);
                    c.setReadTimeout(1600);
                    if (!token.isEmpty()) c.setRequestProperty("X-Library-Token", token);
                    if (c.getResponseCode() == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (InputStream in = c.getInputStream()) {
                            byte[] buf = new byte[1024];
                            int n = in.read(buf);
                            if (n > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                        }
                        if (sb.toString().contains("\"ok\":true")) {
                            log.append(u).append(" \u2192 ok\n");
                            winner = u;
                            break;
                        }
                        log.append(u).append(" \u2192 answered, but not the library\n");
                    } else {
                        log.append(u).append(" \u2192 HTTP ").append(c.getResponseCode()).append("\n");
                    }
                } catch (Exception e) {
                    log.append(u).append(" \u2192 ").append(e.getClass().getSimpleName()).append("\n");
                } finally {
                    if (c != null) c.disconnect();
                }
            }
            final String hit = winner;
            final String tried = log.toString();
            runOnUiThread(() -> {
                if (hit != null) {
                    java.util.List<String> reordered = new java.util.ArrayList<>();
                    reordered.add(hit);
                    for (String s : list) if (!s.equals(hit)) reordered.add(s);
                    saveServers(reordered);
                    load();
                } else {
                    say("No address answered");
                    showUnreachable("nothing answered:\n" + tried.trim());
                }
            });
        }).start();
    }

    /** The address changes when the router hands out a new lease; the list keeps the old ones. */
    void askServers() {
        final java.util.List<String> list = servers();
        String[] labels = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) {
            labels[i] = (i == 0 ? "\u2713 " : "   ") + list.get(i);
        }
        labels[list.size()] = "+ Add an address";
        new AlertDialog.Builder(this)
                .setTitle("Servers, tried in order")
                .setItems(labels, (d, i) -> {
                    if (i == list.size()) { addAddress(list); return; }
                    java.util.List<String> moved = new java.util.ArrayList<>();
                    moved.add(list.get(i));
                    for (String s : list) if (!s.equals(list.get(i))) moved.add(s);
                    saveServers(moved);
                    connect();
                })
                .setNeutralButton("Remove all but the first", (d, w) -> {
                    java.util.List<String> one = new java.util.ArrayList<>();
                    one.add(list.get(0));
                    saveServers(one);
                    connect();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void addAddress(java.util.List<String> list) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        e.setHint("http://100.x.y.z:8787  or  http://name.tailnet.ts.net");
        new AlertDialog.Builder(this)
                .setTitle("Add a server")
                .setMessage("Home Wi-Fi, a VPN address, a machine name - any address that answers /api/health. The list is tried in order.")
                .setView(e)
                .setPositiveButton("Add", (d, w) -> {
                    String v = e.getText().toString().trim();
                    if (v.isEmpty()) return;
                    if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://" + v;
                    java.util.List<String> next = new java.util.ArrayList<>(list);
                    next.add(v.replaceAll("/+$", ""));
                    saveServers(next);
                    connect();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
                if (!token.isEmpty()) c.setRequestProperty("X-Library-Token", token);
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
                    java.util.List<String> next = servers();
                    next.remove(base);
                    next.add(0, v.replaceAll("/+$", ""));
                    saveServers(next);
                    connect();
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
        String[] items = { "Reload", "Servers\u2026", "Test connection", "Check for updates", "Diagnostics" };
        new AlertDialog.Builder(this)
                .setItems(items, (d, i) -> {
                    if (i == 0) connect();
                    else if (i == 1) askServers();
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
                + "Also try    " + servers().subList(Math.min(1, servers().size()), servers().size()) + "\n"
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
