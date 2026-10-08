package com.natestechstuff.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps "My model" loaded in RAM while Jarvis is in the background, so a voice question
 * doesn't wait for a ~1 GB model to load again. Shows an ongoing notification with a Stop action.
 */
public class LocalBrainService extends Service {
    static final String CHANNEL = "jarvis_model";
    static final String ACTION_STOP = "com.natestechstuff.jarvis.STOP_MODEL";
    static final int NOTE_ID = 42;

    public static void start(Context c) {
        Intent i = new Intent(c, LocalBrainService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {
            // background-start restrictions: the model still works, it just isn't pinned
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, LocalBrainService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            LocalBrain.get(this).unload();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground("loading your model…");
        LocalBrain.get(this).ensureLoaded(new LocalBrain.LoadListener() {
            @Override public void onLoaded(String d) { goForeground("model ready · offline · " + shortName(d)); }
            @Override public void onLoadFailed(String e) { stopForeground(true); stopSelf(); }
        });
        return START_STICKY;
    }

    static String shortName(String d) {
        int i = d.indexOf(" · ");
        return i > 0 ? d.substring(0, i) : d;
    }

    private void goForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "NTS Orbit model", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps your on-device model loaded so replies are fast");
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, LocalBrainService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("NTS Orbit · my model")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Unload", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTE_ID, n);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
