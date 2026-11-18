package cn.classfruit.hotpoint;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    private Prefs prefs;
    private Button toggle;
    private TextView tvState, tvSsid, tvUp, tvRoot, tvTraffic;
    private Engine.State last = new Engine.State();
    private boolean busy;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            apply(Engine.parse(intent.getStringExtra(HotspotService.EXTRA_JSON)));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        toggle = findViewById(R.id.btnToggle);
        tvState = findViewById(R.id.tvState);
        tvSsid = findViewById(R.id.tvSsid);
        tvUp = findViewById(R.id.tvUp);
        tvRoot = findViewById(R.id.tvRoot);
        tvTraffic = findViewById(R.id.tvTraffic);

        toggle.setOnClickListener(v -> {
            if (busy) return;
            busy = true;
            tvState.setText(last.enabled() ? "关闭中…" : "开启中…");
            Intent i = new Intent(this, HotspotService.class)
                    .setAction(last.enabled() ? HotspotService.ACTION_STOP : HotspotService.ACTION_START);
            startForegroundService(i);
            toggle.postDelayed(() -> busy = false, 3000);
        });
        findViewById(R.id.btnClients).setOnClickListener(v ->
                startActivity(new Intent(this, ClientsActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnAbout).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));

        new Thread(() -> {
            boolean root = Engine.rootOk();
            runOnUiThread(() -> {
                tvRoot.setVisibility(root ? View.GONE : View.VISIBLE);
                tvRoot.setText("未获得 root：请在 Magisk 里授权本应用");
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, new IntentFilter(HotspotService.ACTION_STATE));
        if (prefs.desired() && !HotspotService.running) {
            startForegroundService(new Intent(this, HotspotService.class)
                    .setAction(HotspotService.ACTION_START));
        }
        refresh();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(receiver);
        super.onPause();
    }

    private void refresh() {
        new Thread(() -> {
            Engine.State s = Engine.status();
            runOnUiThread(() -> apply(s));
        }).start();
    }

    private void apply(Engine.State s) {
        last = s;
        boolean on = s.enabled();
        toggle.setText(on ? "关闭" : "开启");
        toggle.setBackgroundResource(on ? R.drawable.bg_toggle_on : R.drawable.bg_toggle_off);
        if (on) {
            tvState.setText("已开启 · " + s.clients.size() + " 台设备");
        } else if (s.alive) {
            tvState.setText("开启中…");
        } else if (!s.error.isEmpty()) {
            tvState.setText("失败：" + s.error);
        } else if (prefs.desired()) {
            tvState.setText("等待服务启动…");
        } else {
            tvState.setText("已关闭");
        }
        String ssid = s.ssid.isEmpty() ? prefs.ssid() : s.ssid;
        tvSsid.setText(prefs.sec() == 1 ? ssid + " · 密码 " + prefs.pass() : ssid + " · 开放网络");
        if (s.upstream.isEmpty()) {
            tvUp.setText("无上游网络：开 eSIM 流量后才有网");
        } else {
            tvUp.setText("上游：" + s.upstream + "（可上网）");
        }
        tvTraffic.setText(s.enabled() ? "本次流量  ↑" + fmt(s.tx) + "  ↓" + fmt(s.rx) : "");
    }

    private static String fmt(long bytes) {
        if (bytes >= 1048576) return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / 1048576.0);
        return String.format(java.util.Locale.ROOT, "%.0fKB", bytes / 1024.0);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK || keyCode == android.view.KeyEvent.KEYCODE_F1) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}