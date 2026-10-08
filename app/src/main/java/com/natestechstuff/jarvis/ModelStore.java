package com.natestechstuff.jarvis;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies a .gguf the user picked (Downloads, Files app, SD card...) into the app's private storage. */
public final class ModelStore {
    public interface Progress {
        void onProgress(long copied, long total);
        void onDone(File model);
        void onError(String error);
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "models");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return cur.getString(i);
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "model.gguf" : last;
    }

    static long size(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !cur.isNull(i)) return cur.getLong(i);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    static String safeName(String n) {
        String s = n.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!s.toLowerCase().endsWith(".gguf")) s += ".gguf";
        return s;
    }

    /** True when the first 4 bytes are the GGUF magic. */
    static boolean isGguf(byte[] head, int n) {
        return n >= 4 && head[0] == 'G' && head[1] == 'G' && head[2] == 'U' && head[3] == 'F';
    }

    public static void importModel(Context ctx, Uri uri, Progress p) {
        final Context c = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String name = safeName(displayName(c, uri));
            long total = size(c, uri);
            File d = dir(c);
            File tmp = new File(d, name + ".part");
            File out = new File(d, name);
            try {
                long free = new StatFs(d.getPath()).getAvailableBytes();
                if (total > 0 && free < total + 200L * 1024 * 1024) {
                    throw new Exception("not enough free storage (need " + (total >> 20) + " MB, have " + (free >> 20) + " MB)");
                }
                try (InputStream in = c.getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(tmp)) {
                    if (in == null) throw new Exception("couldn't open that file");
                    byte[] buf = new byte[1 << 20];
                    long copied = 0, lastPost = 0;
                    boolean first = true;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (first) {
                            first = false;
                            if (!isGguf(buf, n)) throw new Exception("that's not a .gguf model file");
                        }
                        os.write(buf, 0, n);
                        copied += n;
                        if (copied - lastPost > (8 << 20)) {
                            lastPost = copied;
                            final long cp = copied;
                            main.post(() -> p.onProgress(cp, total));
                        }
                    }
                    if (copied < 1024) throw new Exception("file is empty or incomplete");
                }
                if (out.exists() && !out.delete()) throw new Exception("couldn't replace the old copy");
                if (!tmp.renameTo(out)) throw new Exception("couldn't save the model");
                // keep only the newest model so storage doesn't fill up
                File[] all = d.listFiles();
                if (all != null) for (File f : all) if (!f.equals(out)) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                main.post(() -> p.onDone(out));
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> p.onError(msg));
            }
        }, "model-import").start();
    }

    private ModelStore() {}
}
