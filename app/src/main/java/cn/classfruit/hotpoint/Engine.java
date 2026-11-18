package cn.classfruit.hotpoint;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Root shell bridge to assets/hotspot.sh deployed under /data/local/tmp/dgsh. */
public class Engine {

    public static final String DIR = "/data/local/tmp/dgsh";
    public static final String SCRIPT = DIR + "/hotspot.sh";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public static class Client {
        public String mac = "";
        public String ip = "";
    }

    public static class State {
        public boolean ok;
        public String error = "";
        public String raw = "";
        public boolean alive;
        public String state = "DISABLED";
        public String ssid = "";
        public String upstream = "";
        public boolean sta;
        public long tx;
        public long rx;
        public List<Client> clients = new ArrayList<>();

        public boolean enabled() {
            return alive && "ENABLED".equals(state);
        }
    }

    public static String su(String cmd, long timeoutMs) {
        return su(cmd, null, timeoutMs);
    }

    public static String su(String cmd, byte[] stdin, long timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            OutputStream os = p.getOutputStream();
            if (stdin != null) os.write(stdin);
            os.close();
            final Process fp = p;
            Future<String> f = IO.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    InputStream is = fp.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    return bos.toString("UTF-8");
                }
            });
            String out = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            fp.waitFor();
            return out;
        } catch (TimeoutException te) {
            if (p != null) p.destroyForcibly();
            return null;
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return null;
        }
    }

    public static boolean rootOk() {
        String out = su("id", 8000);
        return out != null && out.contains("uid=0");
    }

    public static boolean deploy(Context ctx) {
        try {
            String out = su("mkdir -p " + DIR + " && cat > " + SCRIPT + " && chmod 755 " + SCRIPT,
                    readAsset(ctx, "hotspot.sh"), 10000);
            if (out == null) return false;
            out = su("cat > " + DIR + "/wiphy && chmod 755 " + DIR + "/wiphy",
                    readAsset(ctx, "wiphy"), 20000);
            return out != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readAsset(Context ctx, String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    public static State parse(String raw) {
        State s = new State();
        if (raw == null) {
            s.error = "su 超时/无响应";
            return s;
        }
        String line = null;
        for (String l : raw.split("\n")) {
            l = l.trim();
            if (l.startsWith("{") && l.endsWith("}")) line = l;
        }
        if (line == null) {
            s.error = raw.trim();
            return s;
        }
        s.raw = line;
        try {
            JSONObject j = new JSONObject(line);
            s.ok = j.optBoolean("ok", false);
            s.error = j.optString("error", "");
            s.alive = j.optInt("alive", 0) == 1;
            s.state = j.optString("state", "DISABLED");
            s.ssid = j.optString("ssid", "");
            s.upstream = j.optString("upstream", "");
            s.sta = j.optInt("sta", 0) == 1;
            s.tx = j.optLong("tx", 0);
            s.rx = j.optLong("rx", 0);
            JSONArray a = j.optJSONArray("clients");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject c = a.getJSONObject(i);
                    Client cl = new Client();
                    cl.mac = c.optString("mac");
                    cl.ip = c.optString("ip");
                    s.clients.add(cl);
                }
            }
        } catch (Exception e) {
            s.error = String.valueOf(e.getMessage());
        }
        return s;
    }

    public static State status() {
        return parse(su("sh " + SCRIPT + " status", 15000));
    }

    public static State start(String ssid, int sec, String pass, int chan, int hidden, int maxsta) {
        String cmd = "sh " + SCRIPT + " start " + q(ssid) + " " + sec + " " + q(pass) + " " + chan
                + " " + hidden + " " + maxsta;
        return parse(su(cmd, 40000));
    }

    public static void kick(String mac) {
        su("sh " + SCRIPT + " kick " + q(mac), 10000);
    }

    public static boolean stop() {
        String out = su("sh " + SCRIPT + " stop", 20000);
        return out != null && out.contains("\"ok\":true");
    }

    public static void wifiOff() {
        su("cmd wifi set-wifi-enabled disabled", 10000);
    }

    public static void keepAliveBoost(String pkg) {
        su("dumpsys deviceidle whitelist +" + pkg
                + "; cmd appops set " + pkg + " RUN_IN_BACKGROUND allow"
                + "; cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow", 15000);
    }

    public static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}