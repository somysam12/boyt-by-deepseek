package com.mundo.proxy.gms;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Material-3 style wavy linear progress (same look as Zerax's sign-in loader). */
public final class WavyProgressIndicator extends View {

    private final int color;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private boolean motion = true;

    public WavyProgressIndicator(Context context, int color) {
        super(context);
        this.color = color;
        this.density = getResources().getDisplayMetrics().density;
        setContentDescription("Loading");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float stroke = density * 3f;
        float right = getWidth() - stroke;
        float cy = getHeight() / 2f;
        if (right <= stroke) return;

        double t = motion ? (SystemClock.uptimeMillis() % 1600) / 1600.0 : 0.0;
        // Head of the wave breathes between ~45% and ~71% of the width.
        float head = (right - stroke) * (float) (Math.sin(t * 2 * Math.PI) * 0.13 + 0.58) + stroke;

        paint.setStrokeWidth(stroke);
        // Track (light) after the head.
        paint.setColor((color & 0x00FFFFFF) | 0x22000000);
        canvas.drawLine(head + density * 8f, cy, right, cy, paint);

        // Wave up to the head.
        paint.setColor(color);
        wave.reset();
        float amp = Math.min(density * 3.5f, getHeight() * 0.22f);
        for (float x = stroke; x <= head; x += density) {
            float y = (float) Math.sin(((x - stroke) / (density * 28f)) * Math.PI * 2 - t * Math.PI * 4) * amp + cy;
            if (x == stroke) wave.moveTo(x, y);
            else wave.lineTo(x, y);
        }
        canvas.drawPath(wave, paint);

        // Stop indicator dot at the end of the track.
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(right, cy, stroke / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);

        if (motion && isShown() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.ProgressBar");
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(Math.round(density * 180f), widthSpec),
                resolveSize(Math.round(density * 20f), heightSpec));
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) invalidate();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            motion = Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
            invalidate();
        }
    }
}
