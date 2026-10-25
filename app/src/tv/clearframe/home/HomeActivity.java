package tv.clearframe.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Window;
import android.view.WindowManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HomeActivity extends Activity implements HomeView.Host {

    private static final String PREFS = "home";
    private static final String KEY_APPS = "apps", KEY_WALL = "wallpaper";

    // Wallpapers bundled from CleanFrame, each with its pre-blurred dock material.
    static final String[] WALLPAPERS = {"misty", "cove", "forest", "valley", "night", "bridge"};

    // Default Home row. StbEmu ships as a free and a pro app; whichever is installed shows.
    private static final String[] DEFAULT_APPS = {
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.mvas.stb.emu.free", "com.mvas.stb.emu.pro"};

    // White marks for known services; any other app uses its installed icon.
    private static final Map<String, String> MARKS = new HashMap<>();
    private static final Map<String, String> NAMES = new HashMap<>();
    static {
        MARKS.put("com.google.android.youtube.tv", "youtube");
        MARKS.put("com.netflix.ninja", "netflix");
        MARKS.put("com.amazon.amazonvideo.livingroom", "primevideo");
        NAMES.put("com.google.android.youtube.tv", "YouTube");
        NAMES.put("com.netflix.ninja", "Netflix");
        NAMES.put("com.amazon.amazonvideo.livingroom", "Prime Video");
    }

    // Not shown in All apps: this home screen and the TV's own launchers.
    private static final List<String> HIDDEN = Arrays.asList("tv.clearframe.home",
            "com.google.android.leanbacklauncher", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx");

    private HomeView view;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> markCache = new HashMap<>();
    private int shownWallpaper = -1;

    private final BroadcastReceiver ticks = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { view.invalidate(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshApps(); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Typeface medium = font("fonts/manrope-medium.ttf"), semibold = font("fonts/manrope-semibold.ttf");
        Bitmap[] utils = {asset("icons/art.png"), asset("icons/apps.png"), asset("icons/settings.png")};
        view = new HomeView(this, this, medium, semibold, utils);
        setContentView(view);
        view.requestFocus();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);

        // CI only (ci/emulator-check.sh): runs Clean up TV's read-only connection test.
        if (getIntent().getBooleanExtra("selftest", false)) {
            startActivity(new Intent(this, CleanupActivity.class).putExtra("selftest", true));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(ticks, t);
        loadWallpaper(prefs.getInt(KEY_WALL, 0));
        refreshApps();
        view.showStatus("");
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(ticks);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while Home is showing closes any open panel.
        if (view != null) view.closeOverlays();
    }

    @Override
    public void onBackPressed() { /* a home screen has nothing to go back to */ }

    // ---- assets ----

    private Typeface font(String path) {
        try { return Typeface.createFromAsset(getAssets(), path); }
        catch (RuntimeException e) { return Typeface.DEFAULT; }
    }

    private Bitmap asset(String path) {
        try (InputStream in = getAssets().open(path)) { return BitmapFactory.decodeStream(in); }
        catch (Exception e) { return null; }
    }

    private void loadWallpaper(final int index) {
        final int i = ((index % WALLPAPERS.length) + WALLPAPERS.length) % WALLPAPERS.length;
        if (i == shownWallpaper) return;
        new Thread(() -> {
            final Bitmap photo = asset("wallpapers/" + WALLPAPERS[i] + ".jpg");
            final Bitmap dock = asset("wallpapers/" + WALLPAPERS[i] + "-dock.jpg");
            main.post(() -> {
                if (photo == null) { view.showStatus("Couldn't load that wallpaper."); return; }
                shownWallpaper = i;
                view.setWallpaper(photo, dock);
            });
        }).start();
    }

    // ---- apps ----

    private List<String> homeList() {
        String saved = prefs.getString(KEY_APPS, null);
        if (saved == null) return new ArrayList<>(Arrays.asList(DEFAULT_APPS));
        List<String> out = new ArrayList<>();
        for (String p : saved.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    private HomeView.App app(String pkg, CharSequence fallbackLabel) {
        PackageManager pm = getPackageManager();
        String label = NAMES.get(pkg);
        if (label == null) label = fallbackLabel != null ? fallbackLabel.toString() : pkg;
        Bitmap mark = null;
        String m = MARKS.get(pkg);
        if (m != null) {
            mark = markCache.get(m);
            if (mark == null) { mark = asset("icons/" + m + ".png"); markCache.put(m, mark); }
        }
        android.graphics.drawable.Drawable icon = null;
        if (mark == null) {
            try { icon = pm.getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return new HomeView.App(pkg, label, mark, icon);
    }

    private void refreshApps() {
        PackageManager pm = getPackageManager();
        Map<String, CharSequence> installed = new HashMap<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (HIDDEN.contains(p) || installed.containsKey(p)) continue;
                installed.put(p, ri.loadLabel(pm));
            }
        }
        List<String> wanted = homeList();
        List<HomeView.App> dock = new ArrayList<>();
        for (String p : wanted) if (installed.containsKey(p)) dock.add(app(p, installed.get(p)));
        List<HomeView.App> all = new ArrayList<>();
        for (Map.Entry<String, CharSequence> e : installed.entrySet()) {
            HomeView.App a = app(e.getKey(), e.getValue());
            a.onHome = wanted.contains(a.pkg);
            all.add(a);
        }
        Collections.sort(all, (a, b) -> a.label.compareToIgnoreCase(b.label));
        view.setApps(dock, all);
    }

    // ---- HomeView.Host ----

    @Override
    public void launch(HomeView.App app) {
        Intent i = launchIntent(app.pkg);
        if (i == null) { view.showStatus(app.label + " isn't installed any more."); refreshApps(); return; }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            view.showStatus("Couldn't open " + app.label + ".");
        }
    }

    @Override
    public void toggleOnHome(HomeView.App app) {
        List<String> list = homeList();
        if (list.contains(app.pkg)) list.remove(app.pkg); else list.add(app.pkg);
        prefs.edit().putString(KEY_APPS, TextUtils.join(",", list)).apply();
        refreshApps();
        view.showStatus(list.contains(app.pkg) ? app.label + " added to Home" : app.label + " removed from Home");
    }

    @Override
    public void nextWallpaper() {
        int next = (Math.max(shownWallpaper, 0) + 1) % WALLPAPERS.length;
        prefs.edit().putInt(KEY_WALL, next).apply();
        loadWallpaper(next);
    }

    @Override
    public void openTvSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { view.showStatus("Couldn't open TV settings."); }
    }

    @Override
    public void openCleanup() {
        startActivity(new Intent(this, CleanupActivity.class));
    }
}
