package com.tsuyu.messenger.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/** Circular clipping container for Telegram-style video messages. */
public class CircleVideoView extends FrameLayout {

    public CircleVideoView(Context c) { super(c); init(); }
    public CircleVideoView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        setClipToOutline(true);
    }
}
