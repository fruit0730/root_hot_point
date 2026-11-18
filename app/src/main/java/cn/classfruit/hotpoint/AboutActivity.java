package cn.classfruit.hotpoint;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class AboutActivity extends Activity {

    private static final String AVATAR_URL = "https://classfruit.cn/img1.png";

    private ImageView avatar;
    private TextView placeholder;
    private boolean loading;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        avatar = findViewById(R.id.ivAvatar);
        placeholder = findViewById(R.id.tvAvatarPlaceholder);
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatar.setClipToOutline(true);
        avatar.setOnClickListener(v -> {
            if (!loading && !loaded) loadAvatar();
        });

        TextView name = findViewById(R.id.tvName);
        name.post(() -> {
            name.getPaint().setShader(new LinearGradient(0, 0, Math.max(name.getWidth(), 1), 0,
                    0xFF4F8CFF, 0xFFA855F7, Shader.TileMode.CLAMP));
            name.invalidate();
        });

        String versionName = "1.1";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ((TextView) findViewById(R.id.tvVersion)).setText("版本 " + versionName);
        findViewById(R.id.btnHome).setOnClickListener(v -> open("https://classfruit.cn/"));
        findViewById(R.id.btnGithub).setOnClickListener(v -> open("https://github.com/fruit0730"));
        findViewById(R.id.btnBili).setOnClickListener(v -> open("https://space.bilibili.com/3494376982448554"));
        findViewById(R.id.btnGitee).setOnClickListener(v -> open("https://gitee.com/pipa3288582557"));

        loadAvatar();
    }

    private void loadAvatar() {
        File cache = new File(getFilesDir(), "avatar.png");
        if (cache.exists()) {
            Bitmap b = decode(cache);
            if (b != null) {
                show(b);
                return;
            }
        }
        loading = true;
        new Thread(() -> {
            File tmp = new File(getFilesDir(), "avatar.tmp");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(AVATAR_URL).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                InputStream in = c.getInputStream();
                FileOutputStream out = new FileOutputStream(tmp);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                c.disconnect();
                if (!tmp.renameTo(cache)) {
                    tmp.delete();
                    throw new IllegalStateException("cache rename failed");
                }
                Bitmap b = decode(cache);
                runOnUiThread(() -> {
                    loading = false;
                    if (b != null) show(b);
                    else fail();
                });
            } catch (Exception e) {
                tmp.delete();
                runOnUiThread(() -> {
                    loading = false;
                    fail();
                });
            }
        }).start();
    }

    private Bitmap decode(File f) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 240 && bounds.outHeight / (sample * 2) >= 240) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private void show(Bitmap b) {
        loaded = true;
        avatar.setImageBitmap(b);
        avatar.setVisibility(View.VISIBLE);
        placeholder.setVisibility(View.GONE);
    }

    private void fail() {
        Toast.makeText(this, "头像加载失败，点头像重试", Toast.LENGTH_SHORT).show();
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "手表上没有可用的浏览器", Toast.LENGTH_SHORT).show();
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