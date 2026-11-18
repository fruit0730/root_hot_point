package cn.classfruit.hotpoint;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 5-minute backstop: if LMK killed the service while the AP should be up, revive it. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Prefs p = new Prefs(ctx);
        if (p.desired() && !HotspotService.running) {
            ctx.startForegroundService(new Intent(ctx, HotspotService.class)
                    .setAction(HotspotService.ACTION_START));
        }
    }
}