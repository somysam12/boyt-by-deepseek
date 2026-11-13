package com.mundo.proxy.gms;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mundo.MundoCore;
import com.mundo.R;
import com.mundo.core.gms.GmsAuthClient;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Code-built "Sign in with Google" UI used by GoogleLoginActivity (account chooser)
 * and GoogleWebLoginActivity (WebView login). Same design as Zerax / Mundo++:
 * frosted glass card with cross-window blur (API 31+), Google brand row, app heading,
 * rounded account rows, pill buttons and the wavy progress indicator.
 */
public final class GoogleAuthUi implements AutoCloseable {

    public static final int ACCENT = 0xFF2467EA;

    public final Activity activity;
    public final boolean dark;
    public final int ink;
    public final int muted;

    private final int userId;
    private BlurCompat blur;
    private LinearLayout footer;

    public GoogleAuthUi(Activity activity, int userId) {
        this.activity = activity;
        this.userId = userId;
        dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        ink = dark ? 0xFFF5F7FC : 0xFF182030;
        muted = dark ? 0xFFB5BDCC : 0xFF657083;
    }

    // ---------------------------------------------------------------------
    // Window / card
    // ---------------------------------------------------------------------

    /** Floating glass dialog. Returns the scrollable content column; buttons go in {@link #footer()}. */
    public LinearLayout dialog() {
        AdaptiveDialogLayout root = new AdaptiveDialogLayout();
        root.setPadding(dp(24), dp(compact() ? 12 : 24), dp(24), dp(compact() ? 12 : 14));
        LinearLayout content = column();
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        footer = column();
        footer.setPadding(0, dp(compact() ? 8 : 12), 0, 0);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        activity.setContentView(root);

        Window window = activity.getWindow();
        GlassDrawable glass = new GlassDrawable(dark, dp(30));
        window.setBackgroundDrawable(glass);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.18f);
        window.setLayout(Math.min(dp(compact() ? 560 : 392),
                activity.getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
        window.setGravity(Gravity.CENTER);
        activity.setFinishOnTouchOutside(false);
        if (Build.VERSION.SDK_INT >= 31) blur = new BlurCompat(activity, glass, dp(64), false);
        enter(root);
        return content;
    }

    /** Glass background for a full-window card (WebView login). */
    public void glassSurface(View view, float radiusDp) {
        GlassDrawable glass = new GlassDrawable(dark, dp(radiusDp));
        view.setBackground(glass);
        view.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= 31) blur = new BlurCompat(activity, glass, dp(48), true);
    }

    public LinearLayout footer() {
        return footer;
    }

    public boolean compact() {
        return activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private int availableHeight() {
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= 30) h = BoundsCompat.usableHeight(activity);
        return Math.max(dp(120), h - dp(24));
    }

    @Override
    public void close() {
        if (blur != null) blur.close();
    }

    // ---------------------------------------------------------------------
    // Building blocks
    // ---------------------------------------------------------------------

    public LinearLayout column() {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public void space(LinearLayout parent, int dp) {
        parent.addView(new View(activity), new LinearLayout.LayoutParams(1, dp(dp)));
    }

    public TextView text(String s, int sizeSp, boolean medium) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(ink);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    public TextView caption(String s) {
        TextView t = text(s, 13, false);
        t.setTextColor(muted);
        return t;
    }

    public ImageView image(Drawable d, int sizeDp, String description) {
        ImageView iv = new ImageView(activity);
        iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription(description);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    public Drawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public WavyProgressIndicator loading() {
        return new WavyProgressIndicator(activity, ACCENT);
    }

    /** "G  Sign in with Google" row. */
    public LinearLayout brand(String label) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER);
        row.addView(image(googleLogo(), 20, "Google"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(10));
        row.addView(text(label, 14, true), lp);
        return row;
    }

    /** App icon + "Continue to <app>" + subtitle (stacked in portrait, inline in landscape). */
    public void appHeading(LinearLayout parent, String pkg, String subtitle) {
        String name = appName(pkg);
        if (!compact()) {
            space(parent, 24);
            View icon = image(appIcon(pkg), 56, name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
            lp.gravity = Gravity.CENTER;
            parent.addView(icon, lp);
            space(parent, 16);
            TextView title = text("Continue to " + name, 24, true);
            title.setGravity(Gravity.CENTER);
            parent.addView(title, new LinearLayout.LayoutParams(-1, -2));
            space(parent, 8);
            TextView cap = caption(subtitle);
            cap.setGravity(Gravity.CENTER);
            parent.addView(cap, new LinearLayout.LayoutParams(-1, -2));
            space(parent, 24);
            return;
        }
        space(parent, 6);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(image(appIcon(pkg), 36, name));
        LinearLayout col = column();
        col.addView(text("Continue to " + name, 19, true));
        space(col, 4);
        TextView cap = caption(subtitle);
        cap.setTextSize(12);
        col.addView(cap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginStart(dp(14));
        row.addView(col, lp);
        parent.addView(row);
        space(parent, 10);
    }

    /** Pill button; primary = filled accent. */
    public Button button(String label, boolean primary, Runnable onClick) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? 0xFFFFFFFF : ink);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33FFFFFF : 0x182467EA),
                rounded(primary ? ACCENT : (dark ? 0x18FFFFFF : 0x75FFFFFF), 26), null));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(52));
        b.setMinWidth(0);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setStateListAnimator(null);
        press(b, onClick);
        return b;
    }

    /**
     * Account row: avatar (initial or photo), title, subtitle, chevron.
     * {@code add=true} renders the "+" row used for "Add a Google account".
     */
    public View accountRow(String title, String subtitle, boolean add, Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setMinimumHeight(dp(72));
        rowSurface(row);

        int avatarBg = add ? (dark ? 0x18FFFFFF : 0xFFEFF2F7) : (dark ? 0xFF233650 : 0xFFE3EDFC);
        TextView initial = text(add ? "+" : firstChar(title), 19, true);
        initial.setGravity(Gravity.CENTER);
        initial.setTextColor(add ? muted : (dark ? 0xFFC5D8FF : 0xFF315DA0));
        initial.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout avatar = new FrameLayout(activity);
        avatar.setBackground(rounded(avatarBg, 24));
        avatar.setClipToOutline(true);
        avatar.addView(initial, new FrameLayout.LayoutParams(-1, -1));
        row.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout col = column();
        TextView name = text(title, 15, true);
        name.setMaxLines(2);
        col.addView(name);
        if (subtitle != null && !subtitle.isEmpty()) {
            space(col, 4);
            TextView sub = caption(subtitle);
            sub.setMaxLines(2);
            col.addView(sub);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginStart(dp(13));
        row.addView(col, lp);

        TextView chevron = text("›", 24, false);
        chevron.setTextColor(muted);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);

        press(row, onClick);
        if (!add && subtitle != null && !subtitle.isEmpty()) bindProfile(subtitle, name, avatar, initial);
        return row;
    }

    public void rowSurface(View v) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x22FFFFFF : 0x142467EA),
                rounded(dark ? 0x10FFFFFF : 0x70FFFFFF, 18), rounded(0xFFFFFFFF, 18)));
    }

    // ---------------------------------------------------------------------
    // Motion
    // ---------------------------------------------------------------------

    public void enter(View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(18));
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        if (motionEnabled()) {
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(360)
                    .setInterpolator(new PathInterpolator(0.18f, 0.75f, 0.2f, 1f)).start();
        } else {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
    }

    public void reveal(View v, int index) {
        if (!motionEnabled()) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(8));
        v.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(index, 5) * 35L).setDuration(280)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
    }

    public void press(View v, Runnable onClick) {
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(x -> {
            if (onClick != null) onClick.run();
        });
        v.setOnTouchListener((view, e) -> {
            if (motionEnabled()) {
                int a = e.getActionMasked();
                boolean down = a == MotionEvent.ACTION_DOWN;
                if (down || a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(down ? 0.975f : 1f).scaleY(down ? 0.975f : 1f)
                            .setDuration(down ? 100 : 220)
                            .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
                }
            }
            return false;
        });
    }

    private boolean motionEnabled() {
        return Settings.Global.getFloat(activity.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    public int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------------
    // App info / logo / profile
    // ---------------------------------------------------------------------

    private ApplicationInfo appInfo(String pkg) {
        if (pkg == null) return null;
        try {
            return activity.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (Exception ignored) {
        }
        try {
            // Guest apps live in the virtual package manager.
            return MundoCore.getBPackageManager().getApplicationInfo(pkg, 0, userId);
        } catch (Exception ignored) {
            return null;
        }
    }

    public Drawable appIcon(String pkg) {
        ApplicationInfo ai = appInfo(pkg);
        if (ai != null) {
            try {
                return ai.loadIcon(activity.getPackageManager());
            } catch (Exception ignored) {
            }
        }
        return activity.getPackageManager().getDefaultActivityIcon();
    }

    public String appName(String pkg) {
        ApplicationInfo ai = appInfo(pkg);
        if (ai != null) {
            try {
                return ai.loadLabel(activity.getPackageManager()).toString();
            } catch (Exception ignored) {
            }
        }
        return pkg == null ? "your app" : pkg;
    }

    public Drawable googleLogo() {
        try {
            Context gms = activity.createPackageContext("com.google.android.gms", 0);
            for (String n : new String[]{"googleg_standard_color_24_vd", "auth_ic_googleg", "google_g_logo"}) {
                int id = gms.getResources().getIdentifier(n, "drawable", "com.google.android.gms");
                if (id != 0) return gms.getResources().getDrawable(id, gms.getTheme());
            }
        } catch (Exception ignored) {
        }
        return activity.getDrawable(R.drawable.mundo_auth_google);
    }

    private static String firstChar(String s) {
        if (s == null || s.isEmpty()) return "?";
        return s.substring(0, s.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault());
    }

    private void bindProfile(String email, TextView name, FrameLayout avatar, TextView initial) {
        new Thread(() -> {
            Bundle p = GmsAuthClient.getProfile(userId, email);
            activity.runOnUiThread(() -> applyProfile(p, name, avatar, initial));
        }, "GmsProfile").start();
    }

    private void applyProfile(Bundle p, TextView name, FrameLayout avatar, TextView initial) {
        if (activity.isFinishing() || activity.isDestroyed() || p == null) return;
        String display = p.getString(GmsAuthClient.PROFILE_DISPLAY_NAME, "").trim();
        if (display.isEmpty()) {
            display = (p.getString(GmsAuthClient.PROFILE_GIVEN_NAME, "") + " "
                    + p.getString(GmsAuthClient.PROFILE_FAMILY_NAME, "")).trim();
        }
        if (!display.isEmpty()) {
            name.setText(display);
            initial.setText(firstChar(display));
        }
        byte[] avatarBytes = p.getByteArray(GmsAuthClient.PROFILE_AVATAR);
        if (avatarBytes == null || avatarBytes.length > 65536) return;
        int hash = Arrays.hashCode(avatarBytes);
        if (Integer.valueOf(hash).equals(avatar.getTag())) return;
        Bitmap bmp = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
        if (bmp == null) return;
        avatar.setBackground(new AvatarDrawable(bmp));
        avatar.setTag(hash);
        initial.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------------
    // Drawables / compat
    // ---------------------------------------------------------------------

    /** Wraps content height to what the screen can show (so long lists scroll). */
    private final class AdaptiveDialogLayout extends LinearLayout {
        AdaptiveDialogLayout() {
            super(activity);
            setOrientation(VERTICAL);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int h = Math.min(getMeasuredHeight(), availableHeight());
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                h = Math.min(h, MeasureSpec.getSize(heightSpec));
            }
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    /** Frosted card: diagonal gradient + 1.5px hairline; more transparent when blur is on. */
    static final class GlassDrawable extends Drawable {
        private final boolean dark;
        private final float radius;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean blurred;

        GlassDrawable(boolean dark, float radius) {
            this.dark = dark;
            this.radius = radius;
        }

        void setBlurEnabled(boolean on) {
            blurred = on;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            RectF r = new RectF(getBounds());
            int start = dark ? (blurred ? 0xBF293344 : 0xF5293344) : (blurred ? 0xDBFFFFFF : 0xF5FFFFFF);
            int end = dark ? (blurred ? 0xCF172131 : 0xFF172131) : (blurred ? 0xBFEAF1FA : 0xFFEDF2F9);
            paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, start, end, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(r, radius, radius, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(dark ? 0x40FFFFFF : 0xC0FFFFFF);
            r.inset(1f, 1f);
            canvas.drawRoundRect(r, radius, radius, paint);
        }

        @Override
        public void getOutline(Outline outline) {
            outline.setRoundRect(getBounds(), radius);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Circular-cropped profile photo. */
    static final class AvatarDrawable extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();

        AvatarDrawable(Bitmap bitmap) {
            this.bitmap = bitmap;
            paint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.max(b.width() / (float) bitmap.getWidth(), b.height() / (float) bitmap.getHeight());
            matrix.setScale(s, s);
            matrix.postTranslate(b.exactCenterX() - bitmap.getWidth() * s / 2f,
                    b.exactCenterY() - bitmap.getHeight() * s / 2f);
            paint.getShader().setLocalMatrix(matrix);
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()) / 2f, paint);
        }

        @Override
        public void getOutline(Outline outline) {
            outline.setOval(getBounds());
            outline.setAlpha(1f);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Cross-window blur behind the card when the device supports it (API 31+). */
    @TargetApi(31)
    static final class BlurCompat {
        private final Activity activity;
        private final Consumer<Boolean> listener;

        BlurCompat(Activity activity, GlassDrawable glass, int radius, boolean background) {
            this.activity = activity;
            listener = enabled -> {
                glass.setBlurEnabled(enabled);
                Window w = activity.getWindow();
                if (!background) {
                    w.setBackgroundBlurRadius(enabled ? radius : 0);
                    return;
                }
                WindowManager.LayoutParams a = w.getAttributes();
                if (enabled) a.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                else a.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                a.setBlurBehindRadius(enabled ? radius : 0);
                w.setAttributes(a);
            };
            WindowManager wm = activity.getWindowManager();
            wm.addCrossWindowBlurEnabledListener(activity.getMainExecutor(), listener);
            listener.accept(wm.isCrossWindowBlurEnabled());
        }

        void close() {
            activity.getWindowManager().removeCrossWindowBlurEnabledListener(listener);
        }
    }

    @TargetApi(30)
    static final class BoundsCompat {
        static int usableHeight(Activity activity) {
            WindowMetrics m = activity.getWindowManager().getCurrentWindowMetrics();
            Insets in = m.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return m.getBounds().height() - in.top - in.bottom;
        }
    }
}
