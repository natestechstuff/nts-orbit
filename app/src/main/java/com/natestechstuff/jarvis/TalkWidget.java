package com.natestechstuff.jarvis;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen widget: one tap opens Jarvis already listening. */
public class TalkWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_TALK, true);
        PendingIntent pi = PendingIntent.getActivity(c, 2, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_talk);
            v.setOnClickPendingIntent(R.id.widget_root, pi);
            mgr.updateAppWidget(id, v);
        }
    }
}
