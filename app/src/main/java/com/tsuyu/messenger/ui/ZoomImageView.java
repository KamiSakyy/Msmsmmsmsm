package com.tsuyu.messenger.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/** Pinch-to-zoom + pan image view for full-screen photo viewing. */
public class ZoomImageView extends AppCompatImageView {

    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;

    private float scale = 1f;
    private static final float MIN = 1f, MAX = 6f;

    private final PointF last = new PointF();
    private boolean dragging;

    public ZoomImageView(Context c) { this(c, null); }

    public ZoomImageView(Context c, AttributeSet a) {
        super(c, a);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float factor = d.getScaleFactor();
                float next = Math.max(MIN, Math.min(MAX, scale * factor));
                factor = next / scale;
                scale = next;
                matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                setImageMatrix(matrix);
                return true;
            }
        });
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) fit();
    }

    private void fit() {
        if (getDrawable() == null) return;
        float vw = getWidth(), vh = getHeight();
        float dw = getDrawable().getIntrinsicWidth();
        float dh = getDrawable().getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float s = Math.min(vw / dw, vh / dh);
        matrix.reset();
        matrix.postScale(s, s);
        matrix.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f);
        scale = 1f;
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                last.set(e.getX(), e.getY());
                dragging = scale > 1f;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress()) {
                    matrix.postTranslate(e.getX() - last.x, e.getY() - last.y);
                    last.set(e.getX(), e.getY());
                    setImageMatrix(matrix);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (scale <= 1.02f) fit();
                break;
        }
        return true;
    }
}
