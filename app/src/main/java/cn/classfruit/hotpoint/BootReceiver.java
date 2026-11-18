package cn.classfruit.hotpoint;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Prefs p = new Prefs(ctx);
        if (p.bootStart() && p.desired()) {
            ctx.startForegroundService(new Intent(ctx, HotspotService.class)
                    .setAction(HotspotService.ACTION_START));
        }
    }
}