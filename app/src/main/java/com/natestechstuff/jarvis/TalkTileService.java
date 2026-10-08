package com.natestechstuff.jarvis;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: pull down the shade, tap, talk. */
public class TalkTileService extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_INACTIVE);
            t.setLabel(getString(R.string.tile_label));
            t.updateTile();
        }
    }

    @Override
    public void onClick() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_TALK, true);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapseCompat(i);
        }
    }

    @SuppressWarnings("deprecation")
    private void startActivityAndCollapseCompat(Intent i) {
        startActivityAndCollapse(i);
    }
}
