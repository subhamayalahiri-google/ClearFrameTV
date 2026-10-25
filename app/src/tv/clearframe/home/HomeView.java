package tv.clearframe.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// The whole home screen, drawn on a 1920x1080 stage that is scaled to the TV.
// Geometry and colours follow CleanFrame's Dock layout (nthnclrk/CleanFrame, app/style.css).
final class HomeView extends View {

    interface Host {
        void launch(App app);
        void toggleOnHome(App app);
        void nextWallpaper();
        void openTvSettings();
        void openCleanup();
    }

    static final class App {
        final String pkg, label;
        final Bitmap mark;      // white brand mark, or null
        final Drawable icon;    // installed icon, used when there is no mark
        boolean onHome;
        App(String pkg, String label, Bitmap mark, Drawable icon) {
            this.pkg = pkg; this.label = label; this.mark = mark; this.icon = icon;
        }
    }

    private static final float W = 1920, H = 1080;
    // Dock (style.css .dock, .tile, .plate)
    private static final float DOCK_Y = 676, DOCK_MAX_W = 1504, DOCK_H = 232, DOCK_R = 48;
    private static final float SHELF_X = 16, TILE_W = 288, TILE_H = 200, TILE_Y = 16, PITCH = 296, TILE_R = 32;
    // The dock fits its tiles, up to five wide, centred; more than five scroll.
    private float dockX = (W - DOCK_MAX_W) / 2, dockW = DOCK_MAX_W, shelfW = DOCK_MAX_W - 2 * SHELF_X;
    // Top-right controls (style.css .utils, .u)
    private static final float UTIL_RIGHT = 112, UTIL_Y = 72, UTIL_SIZE = 64, UTIL_GAP = 8, UTIL_ICON = 32;
    // All apps grid
    private static final int GRID_COLS = 5;
    private static final float GRID_X = 140 + 52, GRID_Y = 220, GRID_ROW = 216;

    private static final int INK = 0xFFF4F4F1, SOLID = 0xFFF3F3EF, SOLID_INK = 0xFF141618;

    private static final int AREA_DOCK = 0, AREA_UTILS = 1;
    private static final int OVERLAY_NONE = 0, OVERLAY_APPS = 1, OVERLAY_MENU = 2;
    static final int UTIL_WALLPAPER = 0, UTIL_APPS = 1, UTIL_SETTINGS = 2;
    private static final String[] UTIL_NAMES = {"Next wallpaper", "All apps", "Settings"};
    private static final String[] MENU_ITEMS = {"Clean up TV", "TV settings"};

    private final Host host;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("h:mm", Locale.getDefault());
    private final Typeface medium, semibold;
    private final Bitmap[] utilIcons;
    private final PorterDuffColorFilter darkIcon = new PorterDuffColorFilter(SOLID_INK, PorterDuff.Mode.SRC_IN);

    private Bitmap wallpaper, dockMaterial;
    private List<App> dock = new ArrayList<>();
    private List<App> all = new ArrayList<>();
    private int area = AREA_DOCK, dockIndex, utilIndex, overlay = OVERLAY_NONE, gridIndex, menuIndex;
    private float plateX, trackX, gridScroll;
    private ValueAnimator plateAnim;
    private String status = "";

    HomeView(Context c, Host host, Typeface medium, Typeface semibold, Bitmap[] utilIcons) {
        super(c);
        this.host = host;
        this.medium = medium;
        this.semibold = semibold;
        this.utilIcons = utilIcons;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setWallpaper(Bitmap photo, Bitmap material) {
        wallpaper = photo;
        dockMaterial = material;
        invalidate();
    }

    void setApps(List<App> dockApps, List<App> allApps) {
        String focused = dockIndex < dock.size() ? dock.get(dockIndex).pkg : null;
        dock = dockApps;
        all = allApps;
        dockIndex = 0;
        for (int i = 0; i < dock.size(); i++) if (dock.get(i).pkg.equals(focused)) dockIndex = i;
        if (gridIndex >= all.size()) gridIndex = Math.max(0, all.size() - 1);
        if (dock.isEmpty() && area == AREA_DOCK) area = AREA_UTILS;
        dockW = dock.isEmpty() ? DOCK_MAX_W : Math.min(DOCK_MAX_W, 2 * SHELF_X + dock.size() * PITCH - (PITCH - TILE_W));
        dockX = (W - dockW) / 2;
        shelfW = dockW - 2 * SHELF_X;
        settle(false);
    }

    void showStatus(String s) { status = s == null ? "" : s; invalidate(); }

    void closeOverlays() { overlay = OVERLAY_NONE; status = ""; invalidate(); }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth() / W, getHeight() / H);
        c.drawColor(0xFF1D2A30);
        c.save();
        c.translate((getWidth() - W * s) / 2, (getHeight() - H * s) / 2);
        c.scale(s, s);
        drawPhoto(c, wallpaper);
        drawShades(c);
        if (overlay == OVERLAY_APPS) drawAllApps(c);
        else {
            drawClock(c);
            drawUtils(c);
            drawDock(c);
            if (overlay == OVERLAY_MENU) drawMenu(c);
        }
        drawStatus(c);
        c.restore();
    }

    // Draws the full-screen photo, clipped to whatever region the canvas is clipped to, so a
    // pre-blurred material stays aligned with the photo behind it (CleanFrame's .mat-img).
    private void drawPhoto(Canvas c, Bitmap b) {
        if (b == null) return;
        float scale = Math.max(W / b.getWidth(), H / b.getHeight());
        float sw = W / scale, sh = H / scale;
        int sx = (int) ((b.getWidth() - sw) / 2), sy = (int) ((b.getHeight() - sh) / 2);
        src.set(sx, sy, sx + (int) sw, sy + (int) sh);
        rect.set(0, 0, W, H);
        paint.setAlpha(255);
        c.drawBitmap(b, src, rect, paint);
    }

    private void drawShades(Canvas c) {
        // .shade (top) and .scrim (bottom)
        paint.setShader(new LinearGradient(0, 0, 0, 300, new int[] {0x52040A0C, 0x24040A0C, 0x00040A0C},
                new float[] {0f, .5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, 300, paint);
        paint.setShader(new LinearGradient(0, 560, 0, 1080, new int[] {0x00040A0C, 0x6B040A0C, 0x85040A0C},
                new float[] {0f, .7f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 560, W, 1080, paint);
        paint.setShader(null);
    }

    private void drawClock(Canvas c) {
        text.setTypeface(medium);
        text.setTextSize(48);
        text.setLetterSpacing(-1.5f / 48);
        text.setColor(INK);
        text.setShadowLayer(3, 0, 1, 0x38000000);
        c.drawText(clockFormat.format(new Date()), 140, 72 + 50, text);
        text.clearShadowLayer();
        text.setLetterSpacing(0);
    }

    private float utilX(int i) {
        return W - UTIL_RIGHT - (3 - i) * UTIL_SIZE - (2 - i) * UTIL_GAP;
    }

    private void drawUtils(Canvas c) {
        for (int i = 0; i < 3; i++) {
            float x = utilX(i);
            boolean focus = overlay == OVERLAY_NONE && area == AREA_UTILS && utilIndex == i;
            if (focus) {
                paint.setColor(SOLID);
                rect.set(x, UTIL_Y, x + UTIL_SIZE, UTIL_Y + UTIL_SIZE);
                c.drawRoundRect(rect, 32, 32, paint);
            }
            Bitmap icon = utilIcons[i];
            if (icon != null) {
                float o = (UTIL_SIZE - UTIL_ICON) / 2;
                rect.set(x + o, UTIL_Y + o, x + o + UTIL_ICON, UTIL_Y + o + UTIL_ICON);
                paint.setColorFilter(focus ? darkIcon : null);
                paint.setAlpha(focus ? 255 : 230);
                c.drawBitmap(icon, null, rect, paint);
                paint.setColorFilter(null);
                paint.setAlpha(255);
            }
        }
        if (overlay == OVERLAY_NONE && area == AREA_UTILS) {
            // Icons-only controls reveal their names when focused (interaction-behaviour.md).
            text.setTypeface(medium);
            text.setTextSize(30);
            text.setColor(INK);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setShadowLayer(2, 0, 1, 0x52000000);
            c.drawText(UTIL_NAMES[utilIndex], W - UTIL_RIGHT, UTIL_Y + UTIL_SIZE + 48, text);
            text.clearShadowLayer();
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawDock(Canvas c) {
        rect.set(dockX, DOCK_Y, dockX + dockW, DOCK_Y + DOCK_H);
        clip.reset();
        clip.addRoundRect(rect, DOCK_R, DOCK_R, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        drawPhoto(c, dockMaterial != null ? dockMaterial : wallpaper);
        paint.setColor(0x29080E12); // .mat-veil
        c.drawRect(rect, paint);
        c.restore();
        // .mat::after: one top hairline that follows the radius
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setShader(new LinearGradient(0, DOCK_Y, 0, DOCK_Y + 24, 0x3DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rect.inset(.75f, .75f);
        c.drawRoundRect(rect, DOCK_R, DOCK_R, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);

        if (dock.isEmpty()) {
            text.setTypeface(medium);
            text.setTextSize(36);
            text.setColor(INK);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("Open All apps and hold OK on an app to add it here", dockX + dockW / 2, DOCK_Y + DOCK_H / 2 + 12, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }

        c.save();
        c.clipRect(dockX + SHELF_X, DOCK_Y, dockX + SHELF_X + shelfW, DOCK_Y + DOCK_H);
        float baseX = dockX + SHELF_X - trackX;
        if (overlay == OVERLAY_NONE && area == AREA_DOCK) drawPlate(c, baseX + plateX, DOCK_Y + TILE_Y, TILE_W, TILE_H);
        for (int i = 0; i < dock.size(); i++) {
            float x = baseX + i * PITCH;
            if (x + TILE_W < dockX || x > dockX + dockW) continue;
            drawTile(c, dock.get(i), x, DOCK_Y + TILE_Y, area == AREA_DOCK && dockIndex == i && overlay == OVERLAY_NONE, false);
        }
        c.restore();
    }

    private void drawPlate(Canvas c, float x, float y, float w, float h) {
        rect.set(x, y, x + w, y + h);
        // --plate: linear-gradient(170deg, .30, .16 58%, .22) and a 1px top edge
        paint.setShader(new LinearGradient(x, y, x + h * .17f, y + h, new int[] {0x4DFFFFFF, 0x29FFFFFF, 0x38FFFFFF},
                new float[] {0f, .58f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, TILE_R, TILE_R, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setShader(new LinearGradient(0, y, 0, y + 16, 0x57FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rect.inset(.5f, .5f);
        c.drawRoundRect(rect, TILE_R, TILE_R, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawTile(Canvas c, App app, float x, float y, boolean focus, boolean badge) {
        int alpha = focus ? 255 : 214; // .84 at rest
        // Mark box: left 84, top 34, 120 x 80 (style.css .tile .mark)
        float mx = x + 84, my = y + 34, mw = 120, mh = 80;
        if (app.mark != null) {
            float sc = Math.min(mw / app.mark.getWidth(), mh / app.mark.getHeight());
            float bw = app.mark.getWidth() * sc, bh = app.mark.getHeight() * sc;
            rect.set(mx + (mw - bw) / 2, my + (mh - bh) / 2, mx + (mw + bw) / 2, my + (mh + bh) / 2);
            paint.setAlpha(alpha);
            c.drawBitmap(app.mark, null, rect, paint);
            paint.setAlpha(255);
        } else if (app.icon != null) {
            int size = 80;
            int l = (int) (mx + (mw - size) / 2), t = (int) my;
            app.icon.setBounds(l, t, l + size, t + size);
            app.icon.setAlpha(alpha);
            app.icon.draw(c);
            app.icon.setAlpha(255);
        }
        text.setTypeface(medium);
        text.setTextSize(36);
        text.setLetterSpacing(-.8f / 36);
        text.setColor(INK);
        text.setAlpha(alpha);
        text.setTextAlign(Paint.Align.CENTER);
        CharSequence label = TextUtils.ellipsize(app.label, text, TILE_W - 8, TextUtils.TruncateAt.END);
        c.drawText(label, 0, label.length(), x + TILE_W / 2, y + 128 + 34, text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0);
        text.setAlpha(255);
        if (badge && app.onHome) {
            text.setTypeface(semibold);
            text.setTextSize(22);
            text.setColor(INK);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("On Home", x + TILE_W - 18, y + 34, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawAllApps(Canvas c) {
        // The blurred material behind a dark veil, like CleanFrame's sheets.
        c.save();
        drawPhoto(c, dockMaterial != null ? dockMaterial : wallpaper);
        c.restore();
        paint.setColor(0x8C080E12);
        c.drawRect(0, 0, W, H, paint);
        text.setTypeface(medium);
        text.setTextSize(48);
        text.setLetterSpacing(-1.5f / 48);
        text.setColor(INK);
        c.drawText("All apps", 140, 72 + 50, text);
        text.setLetterSpacing(0);
        text.setTextSize(30);
        text.setColor(0xC7F4F4F1);
        c.drawText("OK opens an app · Hold OK to add it to Home or remove it · Back returns", 140, 72 + 104, text);
        c.save();
        c.clipRect(0, GRID_Y - 16, W, H - 40);
        for (int i = 0; i < all.size(); i++) {
            float x = GRID_X + (i % GRID_COLS) * PITCH;
            float y = GRID_Y + (i / GRID_COLS) * GRID_ROW - gridScroll;
            if (y + TILE_H < GRID_Y - 16 || y > H) continue;
            boolean focus = i == gridIndex;
            if (focus) drawPlate(c, x, y, TILE_W, TILE_H);
            drawTile(c, all.get(i), x, y, focus, true);
        }
        c.restore();
    }

    private void drawMenu(Canvas c) {
        float w = 420, rowH = 64, x = W - UTIL_RIGHT - w, y = UTIL_Y + UTIL_SIZE + 24;
        rect.set(x, y, x + w, y + 24 + MENU_ITEMS.length * (rowH + 8) + 16);
        clip.reset();
        clip.addRoundRect(rect, 32, 32, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        drawPhoto(c, dockMaterial != null ? dockMaterial : wallpaper);
        paint.setColor(0x52080E12);
        c.drawRect(rect, paint);
        c.restore();
        for (int i = 0; i < MENU_ITEMS.length; i++) {
            float ry = y + 20 + i * (rowH + 8);
            if (i == menuIndex) {
                paint.setColor(SOLID);
                rect.set(x + 12, ry, x + w - 12, ry + rowH);
                c.drawRoundRect(rect, 32, 32, paint);
            }
            text.setTypeface(medium);
            text.setTextSize(30);
            text.setColor(i == menuIndex ? SOLID_INK : INK);
            c.drawText(MENU_ITEMS[i], x + 40, ry + 42, text);
        }
    }

    private void drawStatus(Canvas c) {
        if (status.isEmpty()) return;
        text.setTypeface(medium);
        text.setTextSize(30);
        text.setColor(INK);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(2, 0, 1, 0x66000000);
        c.drawText(status, W / 2, H - 56, text);
        text.clearShadowLayer();
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ---- focus and keys ----

    // Moves the focus plate to the focused tile and scrolls the track so it stays inside the shelf.
    private void settle(boolean animate) {
        if (dockIndex >= dock.size()) dockIndex = Math.max(0, dock.size() - 1);
        float target = dockIndex * PITCH;
        float newTrack = trackX;
        if (target - newTrack < 0) newTrack = target;
        if (target + TILE_W - newTrack > shelfW) newTrack = target + TILE_W - shelfW;
        newTrack = Math.min(newTrack, Math.max(0, dock.size() * PITCH - (PITCH - TILE_W) - shelfW));
        final float fromPlate = plateX, fromTrack = trackX, toTrack = newTrack;
        if (plateAnim != null) plateAnim.cancel();
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            plateX = target;
            trackX = toTrack;
            invalidate();
            return;
        }
        plateAnim = ValueAnimator.ofFloat(0, 1).setDuration(180);
        plateAnim.setInterpolator(new DecelerateInterpolator());
        plateAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            plateX = fromPlate + (target - fromPlate) * f;
            trackX = fromTrack + (toTrack - fromTrack) * f;
            invalidate();
        });
        plateAnim.start();
    }

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (isOk(k)) {
            // Held OK acts once: wait for the release or the long press (interaction-behaviour.md).
            e.startTracking();
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) return true;
        if (overlay == OVERLAY_APPS) return gridKey(k) || super.onKeyDown(k, e);
        if (overlay == OVERLAY_MENU) return menuKey(k) || super.onKeyDown(k, e);
        return homeKey(k) || super.onKeyDown(k, e);
    }

    @Override
    public boolean onKeyLongPress(int k, KeyEvent e) {
        if (isOk(k) && overlay == OVERLAY_APPS && gridIndex < all.size()) {
            host.toggleOnHome(all.get(gridIndex));
            return true;
        }
        return super.onKeyLongPress(k, e);
    }

    @Override
    public boolean onKeyUp(int k, KeyEvent e) {
        if (isOk(k)) {
            if (!e.isTracking() || e.isCanceled()) return true;
            ok();
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (overlay != OVERLAY_NONE) closeOverlays();
            else if (area == AREA_UTILS && !dock.isEmpty()) { area = AREA_DOCK; invalidate(); }
            return true; // a home screen has nothing to go back to
        }
        return super.onKeyUp(k, e);
    }

    private void ok() {
        status = "";
        if (overlay == OVERLAY_APPS) {
            if (gridIndex < all.size()) host.launch(all.get(gridIndex));
            return;
        }
        if (overlay == OVERLAY_MENU) {
            overlay = OVERLAY_NONE;
            invalidate();
            if (menuIndex == 0) host.openCleanup(); else host.openTvSettings();
            return;
        }
        if (area == AREA_DOCK) {
            if (dockIndex < dock.size()) host.launch(dock.get(dockIndex));
            return;
        }
        switch (utilIndex) {
            case UTIL_WALLPAPER: host.nextWallpaper(); break;
            case UTIL_APPS: overlay = OVERLAY_APPS; gridScroll = 0; gridIndex = 0; invalidate(); break;
            default: overlay = OVERLAY_MENU; menuIndex = 0; invalidate();
        }
    }

    private boolean homeKey(int k) {
        if (area == AREA_DOCK) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (dockIndex > 0) { dockIndex--; settle(true); } return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (dockIndex < dock.size() - 1) { dockIndex++; settle(true); } return true;
                case KeyEvent.KEYCODE_DPAD_UP: area = AREA_UTILS; invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: return true;
            }
        } else {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (utilIndex > 0) { utilIndex--; invalidate(); } return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (utilIndex < 2) { utilIndex++; invalidate(); } return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (!dock.isEmpty()) { area = AREA_DOCK; invalidate(); } return true;
                case KeyEvent.KEYCODE_DPAD_UP: return true;
            }
        }
        return false;
    }

    private boolean menuKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: if (menuIndex > 0) { menuIndex--; invalidate(); } return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (menuIndex < MENU_ITEMS.length - 1) { menuIndex++; invalidate(); } return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: return true;
        }
        return false;
    }

    private boolean gridKey(int k) {
        int n = all.size(), i = gridIndex;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (i % GRID_COLS > 0) i--; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (i % GRID_COLS < GRID_COLS - 1 && i + 1 < n) i++; break;
            case KeyEvent.KEYCODE_DPAD_UP: if (i - GRID_COLS >= 0) i -= GRID_COLS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (i + GRID_COLS < n) i += GRID_COLS; else if (i / GRID_COLS < (n - 1) / GRID_COLS) i = n - 1; break;
            default: return false;
        }
        gridIndex = i;
        float top = (i / GRID_COLS) * GRID_ROW;
        float visible = H - 40 - GRID_Y - GRID_ROW;
        if (top - gridScroll < 0) gridScroll = top;
        if (top - gridScroll > visible) gridScroll = top - visible;
        invalidate();
        return true;
    }
}
