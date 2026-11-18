package cn.classfruit.hotpoint;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private static final int[] AUTO_OFF_VALUES = {0, 5, 10, 30};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Prefs p = new Prefs(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        EditText etSsid = findViewById(R.id.etSsid);
        EditText etPass = findViewById(R.id.etPass);
        EditText etMax = findViewById(R.id.etMax);
        CheckBox cbSec = findViewById(R.id.cbSec);
        CheckBox cbHidden = findViewById(R.id.cbHidden);
        CheckBox cbBoot = findViewById(R.id.cbBoot);
        RadioGroup rgChan = findViewById(R.id.rgChan);
        Spinner spAutoOff = findViewById(R.id.spAutoOff);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"永不", "5 分钟", "10 分钟", "30 分钟"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAutoOff.setAdapter(adapter);

        etSsid.setText(p.ssid());
        etPass.setText(p.pass());
        etMax.setText(String.valueOf(p.maxsta()));
        cbSec.setChecked(p.sec() == 1);
        cbHidden.setChecked(p.hidden());
        cbBoot.setChecked(p.bootStart());
        rgChan.check(p.chan() == 1 ? R.id.rb1 : p.chan() == 11 ? R.id.rb11 : R.id.rb6);
        int idx = 0;
        for (int i = 0; i < AUTO_OFF_VALUES.length; i++) {
            if (AUTO_OFF_VALUES[i] == p.autoOff()) idx = i;
        }
        spAutoOff.setSelection(idx);

        Button save = findViewById(R.id.btnSave);
        save.setOnClickListener(v -> {
            String ssid = etSsid.getText().toString().trim();
            String pass = etPass.getText().toString();
            boolean sec = cbSec.isChecked();
            int max;
            try {
                max = Integer.parseInt(etMax.getText().toString().trim());
            } catch (NumberFormatException e) {
                max = 8;
            }
            if (ssid.isEmpty() || ssid.length() > 32) {
                Toast.makeText(this, "名称需为 1-32 个字符", Toast.LENGTH_SHORT).show();
                return;
            }
            if (sec && (pass.length() < 8 || pass.length() > 63)) {
                Toast.makeText(this, "密码需为 8-63 位", Toast.LENGTH_SHORT).show();
                return;
            }
            if (max < 1 || max > 16) {
                Toast.makeText(this, "最大连接数 1-16", Toast.LENGTH_SHORT).show();
                return;
            }
            p.setSsid(ssid);
            p.setPass(pass);
            p.setSec(sec ? 1 : 0);
            p.setHidden(cbHidden.isChecked());
            p.setBootStart(cbBoot.isChecked());
            p.setMaxsta(max);
            p.setAutoOff(AUTO_OFF_VALUES[spAutoOff.getSelectedItemPosition()]);
            p.setChan(rgChan.getCheckedRadioButtonId() == R.id.rb1 ? 1
                    : rgChan.getCheckedRadioButtonId() == R.id.rb11 ? 11 : 6);
            if (p.desired()) {
                startForegroundService(new Intent(this, HotspotService.class)
                        .setAction(HotspotService.ACTION_START)
                        .putExtra(HotspotService.EXTRA_RESTART, true));
                Toast.makeText(this, "已保存，正在重启热点", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            }
            finish();
        });
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