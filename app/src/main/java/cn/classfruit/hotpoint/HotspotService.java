package cn.classfruit.hotpoint;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service owning the hotspot lifecycle: starts the root engine,
 * polls it, restarts it if hostapd/dnsmasq die, keeps STA wifi off (single radio),
 * and holds a partial wakelock while the AP is up.
 */
public class HotspotService extends Service {

    public static final String ACTION_START = "cn.classfruit.hotpoint.action.START";
    public static final String ACTION_STOP = "cn.classfruit.hotpoint.action.STOP";
    public static final String ACTION_STATE = "cn.classfruit.hotpoint.action.STATE";
    public static final String EXTRA_RESTART = "restart";
    public static final String EXTRA_JSON = "json";
    public static volatile boolean running = false;

    private static final int NOTIF_ID = 1;
    private static final long POLL_MS = 4000;
    private static final long RESTART_BACKOFF_MS = 15000;

    private HandlerThread worker;
    private Handler h;
    private PowerManager.WakeLock wl;
    private Prefs prefs;
    private boolean desired;
    private boolean boosted;
    private long lastRestart;
    private long lastClientsMs;
    private String lastJson = "";
    private int notifClientCount = -1;
    private String notifText = "";

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = new Prefs(this);
        worker = new HandlerThread("hotspot-worker");
        worker.start();
        h = new Handler(worker.getLooper());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dgsh:hotspot");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("hotspot", "热点状态",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("热点管家"));
        String action = intent == null ? null : intent.getAction();
        boolean restart = intent != null && intent.getBooleanExtra(EXTRA_RESTART, false);
        if (ACTION_STOP.equals(action)) {
            h.post(this::doStop);
        } else if (ACTION_START.equals(action)) {
            h.post(() -> {
                prefs.setDesired(true);
                doStart(restart);
            });
        } else {
            h.post(() -> doStart(false));
        }
        return START_STICKY;
    }

    private void doStart(boolean forceRestart) {
        desired = prefs.desired();
        if (!desired) {
            shutdown();
            return;
        }
        if (!wl.isHeld()) wl.acquire();
        scheduleAlarm(true);
        Engine.deploy(this);
        if (!boosted) {
            Engine.keepAliveBoost(getPackageName());
            boosted = true;
        }
        Engine.State s = Engine.status();
        if (forceRestart || !s.enabled()) {
            Engine.State r = startEngine();
            lastRestart = System.currentTimeMillis();
            if (!r.ok) {
                publish("{\"ok\":false,\"error\":\"" + r.error.replace("\"", "'") + "\"}");
            }
        }
        lastClientsMs = System.currentTimeMillis();
        h.removeCallbacks(poll);
        h.post(poll);
    }

    private Engine.State startEngine() {
        return Engine.start(prefs.ssid(), prefs.sec(), prefs.pass(), prefs.chan(),
                prefs.hidden() ? 1 : 0, prefs.maxsta());
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!desired) return;
            Engine.State s = Engine.status();
            publish(s);
            if (s.sta) Engine.wifiOff();
            if (s.enabled()) {
                long now = System.currentTimeMillis();
                if (!s.clients.isEmpty()) {
                    lastClientsMs = now;
                } else {
                    int timeout = prefs.autoOff();
                    if (timeout > 0 && now - lastClientsMs >= timeout * 60000L) {
                        doStop();
                        return;
                    }
                }
            } else {
                long now = System.currentTimeMillis();
                if (now - lastRestart >= RESTART_BACKOFF_MS) {
                    lastRestart = now;
                    startEngine();
                }
            }
            h.postDelayed(this, POLL_MS);
        }
    };

    private void doStop() {
        desired = false;
        prefs.setDesired(false);
        h.removeCallbacks(poll);
        scheduleAlarm(false);
        Engine.stop();
        if (wl.isHeld()) wl.release();
        publish("{\"ok\":true,\"alive\":0,\"state\":\"DISABLED\",\"ssid\":\"\",\"upstream\":\"\",\"sta\":0,\"clients\":[]}");
        shutdown();
    }

    private void shutdown() {
        stopForeground(true);
        stopSelf();
    }

    private void publish(Engine.State s) {
        if (!s.raw.isEmpty()) {
            publish(s.raw);
            return;
        }
        publish("{\"ok\":false,\"alive\":0,\"state\":\"DISABLED\",\"error\":\""
                + s.error.replace("\"", "'") + "\",\"clients\":[]}");
    }

    private void publish(String json) {
        lastJson = json;
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()).putExtra(EXTRA_JSON, json));
        Engine.State s = Engine.parse(json);
        String text;
        if (s.enabled()) {
            text = "热点已开启 · " + s.clients.size() + " 台设备";
        } else if (!s.error.isEmpty()) {
            text = "失败：" + s.error;
        } else if (desired) {
            text = "开启中…";
        } else {
            text = "热点已关闭";
        }
        if (!text.equals(notifText) || s.clients.size() != notifClientCount) {
            notifText = text;
            notifClientCount = s.clients.size();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, HotspotService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "hotspot")
                .setSmallIcon(R.drawable.ic_stat_hotspot)
                .setContentTitle("热点管家")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, "关闭", stop)
                .build();
    }

    private void scheduleAlarm(boolean on) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(this, 2,
                new Intent(this, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (on) {
            am.setRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 300000, 300000, pi);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacks(poll);
        if (wl.isHeld()) wl.release();
        worker.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}