package com.natestechstuff.jarvis;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.BatteryManager;
import android.provider.AlarmClock;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** v2.3: the phone side of Jarvis's local tools (AlarmClock intents, launcher, torch, battery). */
public final class PhoneTools implements ToolDispatcher.Actions {
    private final Context ctx;   // an Activity when possible (startActivity without NEW_TASK quirks)

    public PhoneTools(Context ctx) { this.ctx = ctx; }

    @Override public String timeDate() {
        return new SimpleDateFormat("h:mm a, EEEE, MMMM d, yyyy", Locale.US).format(new Date());
    }

    @Override public int[] battery() {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int level = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            Intent st = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (level < 0 && st != null) {
                int l = st.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = st.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (l >= 0 && sc > 0) level = Math.round(l * 100f / sc);
            }
            if (level < 0) return null;
            int status = st == null ? -1 : st.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            return new int[]{level, charging ? 1 : 0};
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override public String setTimer(int seconds, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (label != null && !label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        return start(i, "No clock app here takes timers.");
    }

    @Override public String setAlarm(int hour, int minute, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (label != null && !label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        return start(i, "No clock app here takes alarms.");
    }

    @Override public String openApp(String name) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        List<String> labels = new ArrayList<>();
        for (ResolveInfo r : apps) labels.add(String.valueOf(r.loadLabel(pm)));
        int best = ToolDispatcher.bestAppMatch(name, labels);
        if (best < 0) return null;
        ResolveInfo r = apps.get(best);
        Intent launch = pm.getLaunchIntentForPackage(r.activityInfo.packageName);
        if (launch == null) {
            launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(r.activityInfo.packageName, r.activityInfo.name);
        }
        return start(launch, null) == null ? labels.get(best) : null;
    }

    @Override public String flashlight(boolean on) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return "No camera service.";
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    cm.setTorchMode(id, on);
                    return null;
                }
            }
            return "This phone has no flashlight I can reach.";
        } catch (Exception e) {
            return "Flashlight failed (" + e.getClass().getSimpleName() + "). Is the camera in use?";
        }
    }

    private String start(Intent i, String noApp) {
        try {
            if (!(ctx instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return null;
        } catch (ActivityNotFoundException e) {
            return noApp != null ? noApp : "Couldn't open it.";
        } catch (SecurityException e) {
            return "Android blocked it: " + e.getMessage();
        }
    }
}
