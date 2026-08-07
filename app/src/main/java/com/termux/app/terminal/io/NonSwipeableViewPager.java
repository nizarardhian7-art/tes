package com.termux.app.terminal.io;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.viewpager.widget.ViewPager;

/**
 * TermuxMod: a {@link ViewPager} that ignores swipe/drag gestures from the user but still
 * switches pages normally when {@link #setCurrentItem(int)} is called from code (e.g. from
 * the "TYPE" extra key or the text-input back button). This replaces the old swipe-to-reveal
 * gesture for the toolbar's text-input page, which was easy to trigger by accident and hard
 * to discover on a phone.
 */
public class NonSwipeableViewPager extends ViewPager {

    public NonSwipeableViewPager(Context context) {
        super(context);
    }

    public NonSwipeableViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

}
