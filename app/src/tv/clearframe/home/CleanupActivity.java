package tv.clearframe.home;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Turns off preinstalled apps and makes ClearFrame the Home screen, using the TV's own ADB
// service (127.0.0.1:5555) so no computer is needed. The rules follow TV Detox
// (github.com/Ansub/tv-detox, MIT): turn apps off, never uninstall; keep an undo list on the TV;
// only known launchers are turned off for Home, and they are turned back on if Home still
// doesn't open ClearFrame.
public final class CleanupActivity extends Activity {

    private static final String TAG = "ClearFrame";
    private static final String ME = "tv.clearframe.home";
    private static final String HOME_COMPONENT = ME + "/.HomeActivity";
    private static final String UNDO_FILE = "/data/local/tmp/clearframe-disabled.txt";
    private static final String PREFS = "cleanup", KEY_OFF = "turned_off";

    private static final int INK = 0xFFF4F4F1, MUTE = 0xC7F4F4F1, FAINT = 0x8CF4F4F1;
    private static final int SOLID = 0xFFF3F3EF, SOLID_INK = 0xFF141618;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AdbClient adb;
    private SharedPreferences prefs;
    private float k; // stage pixels (1920 wide) to screen pixels
    private Typeface medium, semibold;

    private TextView status;
    private LinearLayout actions, list;
    private TextView homeButton, applyButton, undoButton;
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<String> pendingLaunchers = new ArrayList<>();
    private boolean confirmApply, busy;

    static final class Pkg {
        String pkg, name, verdict;
        boolean off;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        try { medium = Typeface.createFromAsset(getAssets(), "fonts/manrope-medium.ttf"); } catch (RuntimeException e) { medium = Typeface.DEFAULT; }
        try { semibold = Typeface.createFromAsset(getAssets(), "fonts/manrope-semibold.ttf"); } catch (RuntimeException e) { semibold = Typeface.DEFAULT_BOLD; }
        setContentView(build());
        if (getIntent().getBooleanExtra("selftest", false)) selfTest();
        else connect();
    }

    @Override
    protected void onDestroy() {
        worker.execute(() -> { if (adb != null) adb.close(); });
        worker.shutdown();
        super.onDestroy();
    }

    // ---- layout ----

    private int px(float stage) { return Math.round(stage * k); }

    private TextView text(float size, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable pill(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radius));
        return d;
    }

    private TextView button(String label, View.OnClickListener click) {
        final TextView b = text(30, MUTE, medium);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(28), 0, px(28), 0);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? pill(SOLID, 32) : null);
            b.setTextColor(has ? SOLID_INK : MUTE);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(64));
        lp.rightMargin = px(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1D2A30);
        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int wall = getSharedPreferences("home", MODE_PRIVATE).getInt("wallpaper", 0);
        try (InputStream in = getAssets().open("wallpapers/" + HomeActivity.WALLPAPERS[wall % HomeActivity.WALLPAPERS.length] + "-dock.jpg")) {
            Bitmap b = BitmapFactory.decodeStream(in);
            bg.setImageBitmap(b);
        } catch (Exception ignored) { }
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        View veil = new View(this);
        veil.setBackgroundColor(0x99080E12);
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(140), px(72), px(140), px(48));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView title = text(48, INK, medium);
        title.setText("Clean up TV");
        col.addView(title);

        status = text(30, MUTE, medium);
        status.setLineSpacing(px(10), 1);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = px(20);
        sp.bottomMargin = px(28);
        col.addView(status, sp);

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(actions, new LinearLayout.LayoutParams(-1, px(64)));

        TextView legend = text(26, FAINT, medium);
        legend.setText("safe: preinstalled extras and ads · review: unknown, leave on unless you know it · keep: a feature you may use. Apps the TV needs are not listed.");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(28);
        lp.bottomMargin = px(12);
        col.addView(legend, lp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void say(final String s) {
        main.post(() -> status.setText(s));
    }

    private void showActions(boolean connected) {
        actions.removeAllViews();
        if (!connected) {
            View retry = button("Try again", v -> connect());
            actions.addView(retry);
            actions.addView(button("Back", v -> finish()));
            retry.requestFocus();
            return;
        }
        homeButton = button("Make ClearFrame the Home screen", v -> makeHome());
        applyButton = button(applyLabel(), v -> applySelected());
        undoButton = button("Turn everything back on", v -> undoAll());
        actions.addView(homeButton);
        actions.addView(applyButton);
        actions.addView(undoButton);
        actions.addView(button("Done", v -> finish()));
        homeButton.requestFocus();
    }

    private String applyLabel() {
        if (confirmApply) return "Press OK again to turn off " + selected.size();
        return selected.isEmpty() ? "Turn off selected" : "Turn off selected (" + selected.size() + ")";
    }

    // ---- connection ----

    private void connect() {
        if (busy) return;
        busy = true;
        actions.removeAllViews();
        say("Connecting to your TV's debugging service…");
        worker.execute(() -> {
            try {
                if (adb != null) adb.close();
                adb = AdbClient.connect(this, () -> say("Your TV is asking \"Allow USB debugging?\".\n"
                        + "Tick \"Always allow from this computer\", then choose OK. It's ClearFrame asking, from this TV."));
                say("Connected. Reading the apps on this TV…");
                List<Pkg> pkgs = readPackages();
                main.post(() -> { busy = false; showActions(true); showList(pkgs); });
                say(summary(pkgs));
            } catch (ConnectException e) {
                fail("Can't reach the TV's debugging service at 127.0.0.1:5555.\n"
                        + "In Settings → Device Preferences → Developer options, turn on ADB debugging, then choose Try again.\n"
                        + "TV reports: " + adbProps());
            } catch (AdbClient.NotAllowedException e) {
                fail("The TV didn't allow ClearFrame to connect. Choose Try again, then choose OK on the TV's message.");
            } catch (Exception e) {
                Log.w(TAG, "connect", e);
                fail("Couldn't connect: " + e.getMessage() + "\nTV reports: " + adbProps());
            }
        });
    }

    private void fail(final String why) {
        main.post(() -> { busy = false; status.setText(why); showActions(false); });
    }

    // ADB's TCP port settings, read the same way getprop does, to explain a failed connection.
    private static String adbProps() {
        StringBuilder b = new StringBuilder();
        for (String key : new String[] {"service.adb.tcp.port", "persist.adb.tcp.port", "init.svc.adbd"}) {
            String v = "";
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                v = (String) sp.getMethod("get", String.class).invoke(null, key);
            } catch (Exception ignored) { }
            if (b.length() > 0) b.append(", ");
            b.append(key).append('=').append(v == null || v.isEmpty() ? "(not set)" : v);
        }
        return b.toString();
    }

    // ---- reading the app list ----

    private List<Pkg> readPackages() throws Exception {
        Set<String> all = packages(adb.shell("pm list packages"));
        Set<String> off = packages(adb.shell("pm list packages -d"));
        if (all.isEmpty()) throw new Exception("the TV returned no app list");
        // One call reads every package's flags (TV Detox candidates.sh); PERSISTENT apps can't stay off.
        Map<String, String> flags = new HashMap<>();
        String cur = null;
        for (String line : adb.shell("dumpsys package | grep -E 'Package \\[|pkgFlags=\\['").split("\n")) {
            int a = line.indexOf("Package ["), f = line.indexOf("pkgFlags=[");
            if (a >= 0) {
                int e = line.indexOf(']', a);
                cur = e > a ? line.substring(a + 9, e) : null;
            } else if (f >= 0 && cur != null && !flags.containsKey(cur)) {
                flags.put(cur, line.substring(f));
            }
        }
        PackageManager pm = getPackageManager();
        List<Pkg> out = new ArrayList<>();
        for (String p : all) {
            String fl = flags.get(p);
            boolean persistent = fl != null && fl.contains("PERSISTENT");
            Pkg x = new Pkg();
            x.pkg = p;
            x.off = off.contains(p);
            x.verdict = Verdicts.LAUNCHERS.contains(p) ? Verdicts.NEVER : Verdicts.verdict(p, persistent);
            String name = Verdicts.name(p);
            if (name == null) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(p, PackageManager.MATCH_DISABLED_COMPONENTS);
                    CharSequence l = pm.getApplicationLabel(ai);
                    if (l != null && !l.toString().equals(p)) name = l.toString();
                } catch (PackageManager.NameNotFoundException ignored) { }
            }
            x.name = name != null ? name : p;
            out.add(x);
        }
        Collections.sort(out, (a, b) -> {
            int c = Verdicts.order(a.verdict) - Verdicts.order(b.verdict);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    private static Set<String> packages(String output) {
        Set<String> s = new HashSet<>();
        for (String line : output.split("\n")) if (line.startsWith("package:")) s.add(line.substring(8).trim());
        return s;
    }

    private String summary(List<Pkg> pkgs) {
        int safe = 0, review = 0, off = 0;
        for (Pkg p : pkgs) {
            if (p.off) off++;
            else if (Verdicts.SAFE.equals(p.verdict)) safe++;
            else if (Verdicts.REVIEW.equals(p.verdict)) review++;
        }
        return "Connected. " + safe + " safe to turn off, " + review + " to review, " + off + " already off.\n"
                + "OK on an app selects it. OK on an app that's off turns it back on. Turn off a few at a time, then check the TV still works.";
    }

    private void showList(List<Pkg> pkgs) {
        list.removeAllViews();
        for (final Pkg p : pkgs) {
            if (Verdicts.NEVER.equals(p.verdict) && !p.off) continue;
            list.addView(row(p));
        }
    }

    private View row(final Pkg p) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(24), 0, px(24), 0);
        r.setFocusable(true);
        final TextView mark = text(30, INK, semibold);
        final TextView name = text(30, INK, medium);
        final TextView pkg = text(22, FAINT, medium);
        final TextView tag = text(24, INK, semibold);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pkg.setSingleLine(true);
        pkg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(name);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.topMargin = px(6);
        texts.addView(pkg, pl);
        name.setText(p.name);
        pkg.setText(p.pkg);
        tag.setPadding(px(16), px(6), px(16), px(6));
        r.addView(mark, new LinearLayout.LayoutParams(px(56), -2));
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(tag);
        final Runnable paint = () -> {
            boolean focus = r.hasFocus() || r.isFocused();
            boolean sel = selected.contains(p.pkg);
            mark.setText(p.off ? "–" : sel ? "✓" : "");
            tag.setText(p.off ? "off" : p.verdict);
            int tagColor = p.off ? 0x33FFFFFF : Verdicts.SAFE.equals(p.verdict) ? 0x6630A060 : Verdicts.REVIEW.equals(p.verdict) ? 0x66C08A20 : 0x33FFFFFF;
            tag.setBackground(pill(tagColor, 18));
            r.setBackground(focus ? pill(0x40FFFFFF, 24) : sel ? pill(0x1FFFFFFF, 24) : null);
            name.setTextColor(p.off ? FAINT : INK);
        };
        paint.run();
        r.setOnFocusChangeListener((v, has) -> paint.run());
        r.setOnClickListener(v -> {
            if (busy) return;
            if (p.off) { turnOn(p, paint); return; }
            if (Verdicts.NEVER.equals(p.verdict)) return;
            if (!selected.remove(p.pkg)) {
                selected.add(p.pkg);
                if (Verdicts.REVIEW.equals(p.verdict)) say(p.name + " is unknown to these rules. Only turn it off if you know what it does.");
                else if (Verdicts.KEEP.equals(p.verdict)) say(p.name + " carries a feature you may use. Turn it off only if you don't.");
            }
            confirmApply = false;
            applyButton.setText(applyLabel());
            paint.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(96));
        lp.bottomMargin = px(4);
        r.setLayoutParams(lp);
        return r;
    }

    // ---- actions ----

    private Set<String> undoList() {
        return new LinkedHashSet<>(prefs.getStringSet(KEY_OFF, new HashSet<>()));
    }

    // Saves the package on the TV first, so it can always be turned back on, then turns it off.
    // Returns true only if the TV now reports it as off.
    private boolean turnOff(String p) throws Exception {
        String saved = adb.shell("echo " + p + " >> " + UNDO_FILE + " && echo saved");
        if (!saved.contains("saved")) return false;
        Set<String> local = undoList();
        local.add(p);
        prefs.edit().putStringSet(KEY_OFF, local).commit();
        adb.shell("pm disable-user --user 0 " + p);
        return packages(adb.shell("pm list packages -d " + p)).contains(p);
    }

    private boolean turnOnPkg(String p) throws Exception {
        adb.shell("pm enable " + p);
        return !packages(adb.shell("pm list packages -d " + p)).contains(p);
    }

    private void turnOn(final Pkg p, final Runnable paint) {
        busy = true;
        say("Turning " + p.name + " back on…");
        worker.execute(() -> {
            try {
                boolean ok = turnOnPkg(p.pkg);
                main.post(() -> { p.off = !ok; paint.run(); busy = false; });
                say(ok ? p.name + " is back on." : "Couldn't turn " + p.name + " back on.");
            } catch (Exception e) {
                main.post(() -> busy = false);
                say("Lost the connection: " + e.getMessage());
            }
        });
    }

    private void applySelected() {
        if (busy || selected.isEmpty()) {
            if (selected.isEmpty()) say("Select apps in the list first: OK on an app selects it.");
            return;
        }
        if (!confirmApply) {
            confirmApply = true;
            applyButton.setText(applyLabel());
            say("These will be turned off, not uninstalled. \"Turn everything back on\" undoes it:\n" + names(selected));
            return;
        }
        confirmApply = false;
        busy = true;
        final List<String> todo = new ArrayList<>(selected);
        worker.execute(() -> {
            List<String> failed = new ArrayList<>();
            try {
                for (String p : todo) {
                    say("Turning off " + p + "…");
                    if (!turnOff(p)) failed.add(p);
                }
                // Android saves this a few seconds later; restarting before then can undo it (TV Detox).
                say("Saving the change on the TV, this takes 15 seconds…");
                Thread.sleep(15000);
                List<Pkg> pkgs = readPackages();
                main.post(() -> { selected.clear(); busy = false; showActions(true); showList(pkgs); });
                say((failed.isEmpty() ? "Done. " : "Couldn't turn off: " + failed + ". ")
                        + "Check the TV still works: change input, open an app, play something. Then restart the TV once.");
            } catch (Exception e) {
                main.post(() -> busy = false);
                say("Stopped: " + e.getMessage() + ". Anything already turned off is on the undo list.");
            }
        });
    }

    private String names(Set<String> pkgs) {
        StringBuilder b = new StringBuilder();
        for (String p : pkgs) { if (b.length() > 0) b.append(", "); b.append(p); }
        return b.toString();
    }

    private String home() throws Exception {
        String[] lines = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").trim().split("\n");
        return lines[lines.length - 1].trim();
    }

    private void makeHome() {
        if (busy) return;
        busy = true;
        final boolean confirmed = !pendingLaunchers.isEmpty();
        worker.execute(() -> {
            try {
                adb.shell("cmd package set-home-activity " + HOME_COMPONENT);
                String winner = home();
                if (winner.startsWith(ME + "/")) {
                    pendingLaunchers.clear();
                    done("Home already opens ClearFrame. Press Home on the remote to check.");
                    return;
                }
                if (!confirmed) {
                    // Only known launchers that Android doesn't keep running are candidates (TV Detox install.sh).
                    List<String> found = new ArrayList<>(), left = new ArrayList<>();
                    for (String line : adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME").split("\n")) {
                        int slash = line.indexOf('/');
                        if (slash <= 0) continue;
                        String p = line.substring(0, slash).trim();
                        if (p.equals(ME) || found.contains(p) || left.contains(p)) continue;
                        String fl = adb.shell("dumpsys package " + p + " | grep -m1 pkgFlags=");
                        if (Verdicts.LAUNCHERS.contains(p) && !fl.contains("PERSISTENT")) found.add(p); else left.add(p);
                    }
                    if (found.isEmpty()) {
                        done("Home opens " + winner + ", which isn't a known home screen, so it's left on."
                                + (left.isEmpty() ? "" : "\nAlso answering Home: " + left));
                        return;
                    }
                    main.post(() -> {
                        pendingLaunchers.clear();
                        pendingLaunchers.addAll(found);
                        busy = false;
                        homeButton.setText("Press OK again to turn off " + found.size() + " home screen" + (found.size() > 1 ? "s" : ""));
                    });
                    say("Home opens " + winner + ". To make Home open ClearFrame, the TV's own home screen has to be turned off:\n"
                            + found + "\nIt's turned off, not uninstalled, and is turned back on automatically if Home still doesn't open ClearFrame."
                            + (left.isEmpty() ? "" : "\nLeft on (the TV may need them): " + left));
                    return;
                }
                List<String> off = new ArrayList<>();
                for (String p : new ArrayList<>(pendingLaunchers)) {
                    say("Turning off " + p + "…");
                    if (turnOff(p)) off.add(p);
                }
                say("Saving the change on the TV, this takes 15 seconds…");
                Thread.sleep(15000);
                adb.shell("cmd package set-home-activity " + HOME_COMPONENT);
                winner = home();
                main.post(pendingLaunchers::clear);
                if (winner.startsWith(ME + "/")) {
                    done("Done. Home now opens ClearFrame. Restart the TV once to check it starts here.");
                    return;
                }
                // Home still goes elsewhere: turning the launcher off only left the TV without one. Put it back.
                for (String p : off) turnOnPkg(p);
                done("Home still opens " + winner + ", so the home screen that was turned off is back on.");
            } catch (Exception e) {
                main.post(pendingLaunchers::clear);
                done("Stopped: " + e.getMessage());
            }
        });
    }

    private void undoAll() {
        if (busy) return;
        busy = true;
        worker.execute(() -> {
            try {
                Set<String> todo = undoList();
                String file = adb.shell("cat " + UNDO_FILE + " 2>/dev/null");
                for (String line : file.split("\n")) if (!line.trim().isEmpty()) todo.add(line.trim());
                List<String> failed = new ArrayList<>();
                for (String p : todo) {
                    say("Turning " + p + " back on…");
                    if (!turnOnPkg(p)) failed.add(p);
                }
                if (failed.isEmpty()) {
                    adb.shell("rm -f " + UNDO_FILE);
                    prefs.edit().remove(KEY_OFF).commit();
                }
                List<Pkg> pkgs = readPackages();
                main.post(() -> showList(pkgs));
                done(failed.isEmpty() ? "Everything ClearFrame turned off is back on (" + todo.size() + " apps). "
                        + "If that included the TV's home screen, Home opens it again." : "Couldn't turn back on: " + failed);
            } catch (Exception e) {
                done("Stopped: " + e.getMessage());
            }
        });
    }

    private void done(final String message) {
        main.post(() -> {
            busy = false;
            status.setText(message);
            if (homeButton != null) homeButton.setText("Make ClearFrame the Home screen");
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && busy) return true; // don't leave halfway through a change
        return super.onKeyDown(keyCode, event);
    }

    // ---- CI ----

    // Used by ci/emulator-check.sh: connects and runs read-only commands, then logs the result.
    private void selfTest() {
        status.setText("Self test…");
        worker.execute(() -> {
            try {
                adb = AdbClient.connect(this, () -> Log.i(TAG, "SELFTEST waiting for allow"));
                String sdk = adb.shell("getprop ro.build.version.sdk").trim();
                String home = home();
                List<Pkg> pkgs = readPackages();
                Log.i(TAG, "SELFTEST ok sdk=" + sdk + " home=" + home + " packages=" + pkgs.size());
                say("Self test passed: sdk " + sdk + ", " + pkgs.size() + " packages");
            } catch (Exception e) {
                Log.e(TAG, "SELFTEST fail " + e, e);
                say("Self test failed: " + (e.getMessage() != null ? e.getMessage() : e) + "\n" + adbProps());
            }
        });
    }
}
