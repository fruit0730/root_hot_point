package cn.classfruit.hotpoint;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ClientsActivity extends Activity {

    private LinearLayout list;
    private TextView tvCount;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render(Engine.parse(intent.getStringExtra(HotspotService.EXTRA_JSON)));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clients);
        list = findViewById(R.id.llClients);
        tvCount = findViewById(R.id.tvCount);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refresh());
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, new IntentFilter(HotspotService.ACTION_STATE));
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
            runOnUiThread(() -> render(s));
        }).start();
    }

    private void render(Engine.State s) {
        list.removeAllViews();
        tvCount.setText("已连接 " + s.clients.size() + " 台");
        if (s.clients.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(s.enabled() ? "暂无设备连接" : "热点未开启");
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(0xFF9E9E9E);
            empty.setPadding(0, 24, 0, 24);
            list.addView(empty);
            return;
        }
        for (Engine.Client c : s.clients) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_field);
            row.setPadding(16, 8, 8, 8);

            TextView info = new TextView(this);
            info.setText(c.mac + (c.ip.isEmpty() ? "" : "\n" + c.ip));
            info.setTextSize(12);
            info.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            row.addView(info, infoLp);

            Button kick = new Button(this);
            kick.setText("断开");
            kick.setTextSize(12);
            kick.setTextColor(0xFFFF5252);
            kick.setBackgroundResource(R.drawable.bg_btn);
            kick.setMinHeight(0);
            kick.setMinWidth(0);
            kick.setStateListAnimator(null);
            kick.setPadding(16, 8, 16, 8);
            kick.setOnClickListener(v -> new Thread(() -> {
                Engine.kick(c.mac);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                Engine.State ns = Engine.status();
                runOnUiThread(() -> render(ns));
            }).start());
            row.addView(kick);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            list.addView(row, lp);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_F1) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}