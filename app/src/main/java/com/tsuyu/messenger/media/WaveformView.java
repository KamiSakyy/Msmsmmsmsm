package com.tsuyu.messenger.media;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Telegram-style waveform. Dynamic bars for voice, flat bars for music. */
public class WaveformView extends View {

    private int[] bars = new int[]{20, 40, 60, 35, 70, 45, 25, 55};
    private float progress = 0f;
    private boolean flat = false;

    private final Paint inactive = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private SeekListener seekListener;

    public interface SeekListener { void onSeek(float fraction); }

    public WaveformView(Context c) { super(c); init(); }
    public WaveformView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        inactive.setColor(Color.parseColor("#4DFFFFFF"));
        active.setColor(Color.WHITE);
    }

    public void setColors(int activeColor, int inactiveColor) {
        active.setColor(activeColor);
        inactive.setColor(inactiveColor);
        invalidate();
    }

    public void setFlat(boolean f) { flat = f; invalidate(); }

    public void setBars(int[] b) {
        if (b != null && b.length > 0) bars = b;
        invalidate();
    }

    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    public void setSeekListener(SeekListener l) { seekListener = l; }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = bars.length;
        if (n == 0) return;
        float w = getWidth(), h = getHeight();
        float gap = Math.max(2f, w / n * 0.28f);
        float barW = Math.max(2f, (w - gap * (n - 1)) / n);
        float radius = barW / 2f;
        int activeCount = (int) (n * progress);

        for (int i = 0; i < n; i++) {
            float frac = flat ? 0.55f : Math.max(0.12f, Math.min(1f, bars[i] / 100f));
            float bh = Math.max(barW, h * frac);
            float left = i * (barW + gap);
            float top = (h - bh) / 2f;
            rect.set(left, top, left + barW, top + bh);
            canvas.drawRoundRect(rect, radius, radius, i < activeCount ? active : inactive);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (seekListener == null) return super.onTouchEvent(e);
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
            float f = Math.max(0f, Math.min(1f, e.getX() / getWidth()));
            setProgress(f);
            if (e.getAction() == MotionEvent.ACTION_DOWN) getParent()
                    .requestDisallowInterceptTouchEvent(true);
            seekListener.onSeek(f);
            return true;
        }
        return super.onTouchEvent(e);
    }
}
