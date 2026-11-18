package cn.classfruit.hotpoint;

import android.content.Context;
import android.content.SharedPreferences;

/** Device-protected storage so BOOT_COMPLETED can read it before the user unlocks (FBE). */
public class Prefs {

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    public boolean desired() {
        return sp.getBoolean("desired", false);
    }

    public void setDesired(boolean v) {
        sp.edit().putBoolean("desired", v).apply();
    }

    public boolean bootStart() {
        return sp.getBoolean("bootStart", true);
    }

    public void setBootStart(boolean v) {
        sp.edit().putBoolean("bootStart", v).apply();
    }

    public String ssid() {
        return sp.getString("ssid", "OWW212-Hotspot");
    }

    public void setSsid(String v) {
        sp.edit().putString("ssid", v).apply();
    }

    public String pass() {
        return sp.getString("pass", "88888888");
    }

    public void setPass(String v) {
        sp.edit().putString("pass", v).apply();
    }

    public int sec() {
        return sp.getInt("sec", 1);
    }

    public void setSec(int v) {
        sp.edit().putInt("sec", v).apply();
    }

    public int chan() {
        return sp.getInt("chan", 6);
    }

    public void setChan(int v) {
        sp.edit().putInt("chan", v).apply();
    }

    public boolean hidden() {
        return sp.getBoolean("hidden", false);
    }

    public void setHidden(boolean v) {
        sp.edit().putBoolean("hidden", v).apply();
    }

    public int maxsta() {
        return sp.getInt("maxsta", 8);
    }

    public void setMaxsta(int v) {
        sp.edit().putInt("maxsta", v).apply();
    }

    /** minutes without clients before auto-off; 0 = never */
    public int autoOff() {
        return sp.getInt("autoOff", 0);
    }

    public void setAutoOff(int v) {
        sp.edit().putInt("autoOff", v).apply();
    }
}