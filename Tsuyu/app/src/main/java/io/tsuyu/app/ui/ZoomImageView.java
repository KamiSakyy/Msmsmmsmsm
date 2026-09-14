package io.tsuyu.app.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Pinch-zoom image view (TG-style fullscreen viewer). */
public class ZoomImageView extends ImageView {
    private Matrix matrix = new Matrix();
    private ScaleGestureDetector scaleDetector;
    private android.view.GestureDetector gestureDetector;

    private final float[] matrixValues = new float[9];
    private float minScale = 0.5f;
    private float maxScale = 6f;
    private float currentScale = 1f;

    private enum Mode { NONE, DRAG, ZOOM }
    private Mode mode = Mode.NONE;
    private PointF last = new PointF();

    public ZoomImageView(Context context) { super(context); init(context); }
    public ZoomImageView(Context context, AttributeSet attrs) { super(context, attrs); init(context); }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                float desired = currentScale * scaleFactor;
                desired = Math.max(minScale, Math.min(maxScale, desired));
                scaleFactor = desired / currentScale;
                currentScale = desired;
                matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                fixTrans();
                setImageMatrix(matrix);
                return true;
            }
        });
        gestureDetector = new android.view.GestureDetector(context, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null) return;
                matrix.postTranslate(-dx, -dy);
                fixTrans();
                setImageMatrix(matrix);
            }
            @Override
            public boolean onDoubleTap(android.view.MotionEvent e) {
                currentScale = currentScale > 1.5f ? 1f : 2.2f;
                matrix.postScale(currentScale, currentScale, e.getX(), e.getY());
                fixTrans();
                setImageMatrix(matrix);
                return true;
            }
        });
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        fitCenter();
    }

    private void fitCenter() {
        try {
            Drawable d = getDrawable();
            if (d == null) return;
            int iw = d.getIntrinsicWidth();
            int ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) return;
            int vw = getWidth() > 0 ? getWidth() : 1080;
            int vh = getHeight() > 0 ? getHeight() : 1920;
            float scale = Math.min((float) vw / iw, (float) vh / ih);
            float tx = (vw - iw * scale) / 2f;
            float ty = (vh - ih * scale) / 2f;
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(tx, ty);
            currentScale = 1f;
            setImageMatrix(matrix);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw == 0 && oldh == 0) fitCenter();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = scaleDetector.onTouchEvent(ev);
        handled = gestureDetector.onTouchEvent(ev) || handled;
        if (currentScale <= 1f) {
            getParent().requestDisallowInterceptTouchEvent(false);
        } else {
            getParent().requestDisallowInterceptTouchEvent(true);
            int action = ev.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    last.set(ev.getX(), ev.getY());
                    mode = Mode.DRAG;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == Mode.DRAG) {
                        float dx = ev.getX() - last.x;
                        float dy = ev.getY() - last.y;
                        matrix.postTranslate(-dx, -dy);
                        last.set(ev.getX(), ev.getY());
                        fixTrans();
                        setImageMatrix(matrix);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    mode = Mode.NONE;
                    break;
            }
        }
        return true;
    }

    private void fixTrans() {
        matrix.getValues(matrixValues);
        float transX = matrixValues[Matrix.MTRANS_X];
        float transY = matrixValues[Matrix.MTRANS_Y];
        Drawable d = getDrawable();
        if (d == null) return;
        float dw = d.getIntrinsicWidth();
        float dh = d.getIntrinsicHeight();
        float vw = getWidth() > 0 ? getWidth() : 1080;
        float vh = getHeight() > 0 ? getHeight() : 1920;
        float scale = Math.min((float) vw / dw, (float) vh / dh);
        float scaledW = dw * scale * currentScale;
        float scaledH = dh * scale * currentScale;
        float minX = Math.min(0, vw - scaledW);
        float minY = Math.min(0, vh - scaledH);
        if (transX > 0) matrix.postTranslate(-transX, 0);
        else if (transX < minX) matrix.postTranslate(minX - transX, 0);
        if (transY > 0) matrix.postTranslate(0, -transY);
        else if (transY < minY) matrix.postTranslate(0, minY - transY);
    }
}
