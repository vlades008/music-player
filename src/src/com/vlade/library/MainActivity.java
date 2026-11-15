package com.vlade.library;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The phone half of the music library.
 *
 * The app is a container, not the app: everything you see is the page served by
 * the machine at home, so a change to the player arrives without an install.
 * The container only has to do four things Android alone can do - keep playing
 * when the screen is off, put the track on a notification, remember the server
 * address, and notice when a new build of the container itself exists.
 */
public class MainActivity extends Activity {

    static final String PREFS = "cfg";
    static final String KEY_URL = "baseUrl";
    static final String KEY_UPDATE = "updateUrl";
    static final String KEY_SEEN = "seenVersion";
    static final String DEFAULT_URL = "http://192.168.0.12:8787";

    WebView web;
    LinearLayout root;
    FrameLayout stage;
    TextView banner;
    String base;
    Updater.Rel pendingUpdate;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        stage.removeAllViews();
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
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u.getHost() != null && u.getHost().equals(Uri.parse(base).getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onReceivedError(WebView v, android.webkit.WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame()) showUnreachable();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                // Handy for the page: it can hide its own chrome when it runs inside the app.
                v.evaluateJavascript("document.documentElement.classList.add('in-app')", null);
            }
        });
        stage.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(base);
        addMenuButton();
        checkUpdates(false);
    }

    void showUnreachable() {
        stage.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView t = new TextView(this);
        t.setText("Can't reach the library");
        t.setTextColor(Color.parseColor("#f4f2ef"));
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView d = new TextView(this);
        d.setText(base + "\n\nThe machine has to be awake and on the same Wi-Fi. If the address changed, set it again.");
        d.setTextColor(Color.parseColor("#9a9aa4"));
        d.setTextSize(14);
        d.setGravity(Gravity.CENTER);
        d.setPadding(0, dp(12), 0, dp(22));
        box.addView(d);

        Button retry = new Button(this);
        retry.setText("Try again");
        retry.setOnClickListener(v -> load());
        box.addView(retry);

        Button addr = new Button(this);
        addr.setText("Change address");
        addr.setOnClickListener(v -> askAddress());
        box.addView(addr);

        stage.addView(box);
        addMenuButton();
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
        m.setAlpha(0.45f);
        m.setOnClickListener(v -> showMenu());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44));
        lp.gravity = Gravity.TOP | Gravity.END;
        ((FrameLayout) stage).addView(m, lp);
    }

    void showMenu() {
        String[] items = { "Reload", "Server address", "Check for updates", "Version" };
        new AlertDialog.Builder(this)
                .setItems(items, (d, i) -> {
                    if (i == 0) load();
                    else if (i == 1) askAddress();
                    else if (i == 2) checkUpdates(true);
                    else versionInfo();
                })
                .show();
    }

    void versionInfo() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String upd = p.getString(KEY_UPDATE, getString(R.string.update_url));
        new AlertDialog.Builder(this)
                .setTitle("Library " + versionName())
                .setMessage("Container build " + versionCode() + "\nServer " + base
                        + "\n\nUpdate source:\n" + (upd == null || upd.isEmpty() ? "not set" : upd)
                        + "\n\nThe player itself updates with the server - no install needed.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Update source", (d, w) -> askUpdateUrl())
                .show();
    }

    void askUpdateUrl() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_UPDATE, getString(R.string.update_url)));
        new AlertDialog.Builder(this)
                .setTitle("Update source")
                .setMessage("URL of latest.json in your repository, e.g.\nhttps://raw.githubusercontent.com/USER/REPO/main/latest.json")
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
            if (loud) toast("No update source set - see Version > Update source.");
            return;
        }
        if (loud) toast("Checking\u2026");
        Updater.check(url, new Updater.OnCheck() {
            @Override public void done(Updater.Rel rel, String error) {
                runOnUiThread(() -> {
                    if (error != null) { if (loud) toast("Check failed: " + error); return; }
                    if (rel == null) { if (loud) toast("You are on the newest build."); return; }
                    if (rel.code <= versionCode()) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_SEEN, rel.code).apply();
                        if (loud) toast("You are on the newest build (" + versionName() + ").");
                        return;
                    }
                    pendingUpdate = rel;
                    banner.setText("Update " + rel.name + " is ready - tap to install");
                    banner.setVisibility(View.VISIBLE);
                    if (loud) toast("Update " + rel.name + " available");
                });
            }
        });
    }

    void install(Updater.Rel rel) {
        if (rel == null) return;
        banner.setText("Downloading " + rel.name + "\u2026");
        Updater.download(this, rel, new Updater.OnDownload() {
            @Override public void done(Uri apk, String error) {
                runOnUiThread(() -> {
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
                });
            }
        });
    }

    // ---- plumbing -----------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        checkUpdates(false);
    }

    void runJs(String js) {
        if (web != null) runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        PlaybackService.onToggle = null;
        PlaybackService.onNext = null;
        super.onDestroy();
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
