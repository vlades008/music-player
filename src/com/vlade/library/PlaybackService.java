package com.vlade.library;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the process alive while music plays, and puts the current track on a
 * notification with play/pause and next. A plain WebView stops when Android
 * reclaims a backgrounded activity; this is the smallest thing that stops that.
 */
public class PlaybackService extends Service {

    static Runnable onToggle;
    static Runnable onNext;

    static final String CHANNEL = "playback";
    static final int ID = 4242;
    static final String ACTION_TOGGLE = "com.vlade.library.TOGGLE";
    static final String ACTION_NEXT = "com.vlade.library.NEXT";
    static final String ACTION_STOP = "com.vlade.library.STOP";

    static String title = "";
    static String artist = "";
    static boolean playing = false;

    PowerManager.WakeLock lock;
    boolean foreground = false;

    static void show(Context ctx, String t, String a, boolean p) {
        title = t;
        artist = a == null ? "" : a;
        playing = p;
        Intent i = new Intent(ctx, PlaybackService.class).setAction("update");
        ctx.startForegroundService(i);
    }

    static void stop(Context ctx) {
        title = "";
        ctx.stopService(new Intent(ctx, PlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "library:playback");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            if (onToggle != null) onToggle.run();
            return START_STICKY;
        }
        if (ACTION_NEXT.equals(action)) {
            if (onNext != null) onNext.run();
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (title == null || title.isEmpty()) {
            // A service started with startForegroundService must call startForeground
            // before it stops, or Android kills the process for being late.
            startForeground(ID, placeholder());
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(ID, build());
        foreground = true;
        if (playing && !lock.isHeld()) lock.acquire(6 * 60 * 60 * 1000L);
        if (!playing && lock.isHeld()) lock.release();
        return START_STICKY;
    }

    Notification placeholder() {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Library")
                .setContentText("Stopped")
                .setOngoing(false)
                .build();
    }

    Notification build() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent next = PendingIntent.getService(this, 2,
                new Intent(this, PlaybackService.class).setAction(ACTION_NEXT), PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(open)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(toggleIcon(), playing ? "Pause" : "Play", toggle).build())
                .addAction(new Notification.Action.Builder(toggleIcon(), "Next", next).build());
        if (playing) b.setStyle(new Notification.BigTextStyle().bigText(artist));
        return b.build();
    }

    android.graphics.drawable.Icon toggleIcon() {
        return android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher_foreground);
    }

    @Override
    public void onDestroy() {
        if (lock != null && lock.isHeld()) lock.release();
        if (foreground) stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
