package com.sanduo.pdaregistry;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import java.util.function.Consumer;

/** A horizontal reveal gesture never deletes by itself; the exposed button must be tapped. */
public final class SwipeDeleteLayout extends FrameLayout {
  private final View content;
  private final Button delete;
  private final Consumer<SwipeDeleteLayout> onReveal;
  private final int actionWidth, touchSlop;
  private final float flingSpeed;
  private float downX, downY, initialOffset;
  private boolean dragging, vertical, tapToClose;
  private VelocityTracker velocity;

  public SwipeDeleteLayout(
      Context context, View content, Button delete, Consumer<SwipeDeleteLayout> onReveal) {
    super(context);
    this.content = content;
    this.delete = delete;
    this.onReveal = onReveal;
    float density = getResources().getDisplayMetrics().density;
    actionWidth = Math.round(72 * density);
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    flingSpeed = 600 * density;
    setClipChildren(true);
    addView(delete, new LayoutParams(actionWidth, LayoutParams.MATCH_PARENT, Gravity.LEFT));
    addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    setDeleteVisible(false);
  }

  private void setDeleteVisible(boolean visible) {
    delete.setVisibility(visible ? VISIBLE : INVISIBLE);
    delete.setImportantForAccessibility(
        visible ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  private void begin(MotionEvent event) {
    content.animate().cancel();
    downX = event.getX();
    downY = event.getY();
    initialOffset = content.getTranslationX();
    dragging = vertical = false;
    boolean overDelete =
        initialOffset > 0
            ? downX < initialOffset
            : initialOffset < 0 && downX > getWidth() + initialOffset;
    tapToClose = initialOffset != 0 && !overDelete;
    releaseVelocity();
    velocity = VelocityTracker.obtain();
    velocity.addMovement(event);
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      begin(event);
      return tapToClose;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !vertical) {
      float dx = event.getX() - downX, dy = event.getY() - downY;
      if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
        vertical = true;
        tapToClose = false;
        // Cancel the card's pending tap even when a short list cannot scroll.
        // Leave interception allowed so the surrounding ScrollView can take over.
        return true;
      } else if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
        startDragging();
        return true;
      }
    }
    if (event.getActionMasked() == MotionEvent.ACTION_UP
        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) releaseVelocity();
    return false;
  }

  private void startDragging() {
    if (dragging) return;
    dragging = true;
    tapToClose = false;
    onReveal.accept(this);
    getParent().requestDisallowInterceptTouchEvent(true);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (velocity != null) velocity.addMovement(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        return true;
      case MotionEvent.ACTION_MOVE:
        float dx = event.getX() - downX, dy = event.getY() - downY;
        if (!dragging && Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
          vertical = true;
          tapToClose = false;
          return true;
        }
        if (!vertical && (dragging || Math.abs(dx) > touchSlop)) {
          startDragging();
          moveContent(Math.max(-actionWidth, Math.min(actionWidth, initialOffset + dx)));
        }
        return true;
      case MotionEvent.ACTION_UP:
        if (dragging) {
          velocity.computeCurrentVelocity(1000);
          float speed = velocity.getXVelocity(), offset = content.getTranslationX();
          boolean open = Math.abs(offset) > actionWidth / 2f;
          if (Math.abs(speed) > flingSpeed) open = speed * offset > 0;
          settle(open ? Math.signum(offset) * actionWidth : 0);
        } else if (tapToClose) {
          close();
          performClick();
        }
        finishGesture();
        return true;
      case MotionEvent.ACTION_CANCEL:
        settle(initialOffset == 0 ? 0 : Math.signum(initialOffset) * actionWidth);
        finishGesture();
        return true;
      default:
        return true;
    }
  }

  private void moveContent(float offset) {
    if (offset != 0) {
      LayoutParams params = (LayoutParams) delete.getLayoutParams();
      int gravity = offset > 0 ? Gravity.LEFT : Gravity.RIGHT;
      if (params.gravity != gravity) {
        params.gravity = gravity;
        delete.setLayoutParams(params);
      }
    }
    setDeleteVisible(offset != 0);
    content.setTranslationX(offset);
  }

  private void settle(float offset) {
    content.animate().cancel();
    if (offset != 0) {
      LayoutParams params = (LayoutParams) delete.getLayoutParams();
      params.gravity = offset > 0 ? Gravity.LEFT : Gravity.RIGHT;
      delete.setLayoutParams(params);
      setDeleteVisible(true);
    }
    content
        .animate()
        .translationX(offset)
        .setDuration(160)
        .withEndAction(() -> setDeleteVisible(content.getTranslationX() != 0))
        .start();
  }

  public void close() {
    settle(0);
  }

  private void finishGesture() {
    dragging = tapToClose = false;
    getParent().requestDisallowInterceptTouchEvent(false);
    releaseVelocity();
  }

  private void releaseVelocity() {
    if (velocity != null) {
      velocity.recycle();
      velocity = null;
    }
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  protected void onDetachedFromWindow() {
    content.animate().cancel();
    releaseVelocity();
    super.onDetachedFromWindow();
  }
}
