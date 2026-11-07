/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.android;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import androidx.appcompat.widget.AppCompatEditText;

/** Keeps Android text selection gestures while reporting confirmed word taps. */
public final class CheckingEditText extends AppCompatEditText {
    public interface OnWordTapListener { void onWordTap(int offset); }
    private final GestureDetector gestures;
    private OnWordTapListener listener;
    private int tappedOffset;

    public CheckingEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (listener != null) listener.onWordTap(tappedOffset);
                return false;
            }
        });
    }

    public void setOnWordTapListener(OnWordTapListener listener) { this.listener = listener; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        // Capture the text position before opening the IME can resize/scroll us.
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            tappedOffset = getOffsetForPosition(event.getX(), event.getY());
        }
        boolean handled = super.onTouchEvent(event);
        boolean gestureHandled = gestures.onTouchEvent(event);
        return handled || gestureHandled;
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override protected void onDetachedFromWindow() {
        MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        gestures.onTouchEvent(cancel);
        cancel.recycle();
        super.onDetachedFromWindow();
    }
}
