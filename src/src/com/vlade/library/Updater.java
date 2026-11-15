package com.vlade.library;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Update check without a store and without a token: the repository carries one
 * small latest.json next to the APK, and the app compares its own versionCode
 * against it. A public repository is enough - no account, no secret in the app.
 */
public class Updater {

    public static class Rel {
        public int code;
        public String name = "";
        public String apk = "";
        public String notes = "";
    }

    public interface OnCheck { void done(Rel rel, String error); }
    public interface OnDownload { void done(Uri apk, String error); }

    static final int TIMEOUT = 12000;

    public static void check(String url, OnCheck cb) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(TIMEOUT);
                c.setReadTimeout(TIMEOUT);
                c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("User-Agent", "LibraryShell");
                int code = c.getResponseCode();
                if (code != 200) { cb.done(null, "HTTP " + code); return; }
                StringBuilder sb = new StringBuilder();
                try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                }
                JSONObject j = new JSONObject(sb.toString());
                Rel r = new Rel();
                r.code = j.optInt("versionCode", 0);
                r.name = j.optString("versionName", "");
                r.apk = absolute(url, j.optString("apk", ""));
                r.notes = j.optString("notes", "");
                if (r.code <= 0) { cb.done(null, "latest.json has no versionCode"); return; }
                cb.done(r, null);
            } catch (Exception e) {
                cb.done(null, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    public static void download(Context ctx, Rel rel, OnDownload cb) {
        new Thread(() -> {
            HttpURLConnection c = null;
            Uri out = null;
            try {
                String name = "Library-" + (rel.name.isEmpty() ? String.valueOf(rel.code) : rel.name) + ".apk";
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                out = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (out == null) { cb.done(null, "could not create the download"); return; }

                c = (HttpURLConnection) new URL(rel.apk).openConnection();
                c.setConnectTimeout(TIMEOUT);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "LibraryShell");
                int code = c.getResponseCode();
                if (code != 200) { cb.done(null, "HTTP " + code); return; }

                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     OutputStream os = ctx.getContentResolver().openOutputStream(out)) {
                    if (os == null) { cb.done(null, "no output stream"); return; }
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                ctx.getContentResolver().update(out, done, null, null);
                cb.done(out, null);
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (out != null) { try { ctx.getContentResolver().delete(out, null, null); } catch (Exception ignored) {} }
                cb.done(null, msg);
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    /** A relative apk path in latest.json is resolved against the json's own folder. */
    static String absolute(String base, String maybe) {
        if (maybe == null || maybe.isEmpty()) return "";
        if (maybe.startsWith("http://") || maybe.startsWith("https://")) return maybe;
        int cut = base.lastIndexOf('/');
        return (cut > 0 ? base.substring(0, cut + 1) : base) + maybe.replaceAll("^/+", "");
    }
}
