package com.kenan.optishare;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

public class RadarView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulse = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float phase = 0f;
    private ValueAnimator animator;

    public RadarView(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(1.2f));
        ring.setColor(Color.argb(85, 73, 190, 255));
        core.setColor(Color.rgb(73, 190, 255));
        start();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void start() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1800);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float max = Math.min(getWidth(), getHeight()) * 0.44f;

        for (int i = 1; i <= 3; i++) canvas.drawCircle(cx, cy, max * i / 3f, ring);

        float r = max * (0.25f + 0.75f * phase);
        int alpha = (int) (150 * (1f - phase));
        pulse.setShader(new RadialGradient(cx, cy, Math.max(dp(2), r),
                new int[]{Color.argb(alpha, 73, 190, 255), Color.argb(0, 73, 190, 255)},
                null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, pulse);
        pulse.setShader(null);

        canvas.drawCircle(cx, cy, dp(8), core);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(Color.argb(45, 73, 190, 255));
        canvas.drawCircle(cx, cy, dp(22), glow);
    }
}
