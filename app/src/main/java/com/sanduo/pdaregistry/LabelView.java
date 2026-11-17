package com.sanduo.pdaregistry;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import com.sanduo.pdaregistry.Models.Record;

/** Coordinates and font sizes share the millimetre scale used by the printer SDK. */
public final class LabelView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF selectionBox = new RectF();
  private LabelTemplate template;
  private Record record;
  public int selected = -1;

  public interface OnMove {
    void moved(int line);
  }

  public OnMove onMove;
  private float startX, startY, originalX, originalY;

  public LabelView(Context context, LabelTemplate template, Record record) {
    super(context);
    this.template = template;
    this.record = record;
    setContentDescription("30乘20毫米标签预览");
    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
  }

  public void update(LabelTemplate template, Record record, int selected) {
    this.template = template;
    this.record = record;
    this.selected = selected;
    invalidate();
  }

  @Override
  protected void onMeasure(int ws, int hs) {
    int width =
        Math.min(
            MeasureSpec.getSize(ws), Math.round(264 * getResources().getDisplayMetrics().density));
    setMeasuredDimension(width, Math.round(width * 2f / 3f));
  }

  public static float fitFont(Paint paint, String text, float maxWidth, float desired) {
    paint.setTextSize(desired);
    float measured = paint.measureText(text);
    return measured > maxWidth && measured > 0 ? desired * maxWidth / measured : desired;
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.drawColor(Color.WHITE);
    if (record == null) return;
    float scale = getWidth() / 30f;
    for (int i = 0; i < template.lines.size(); i++) {
      LabelTemplate.Line l = template.lines.get(i);
      String text = l.resolve(record);
      paint.setColor(Color.BLACK);
      paint.setTypeface(Typeface.create("sans-serif", l.bold ? Typeface.BOLD : Typeface.NORMAL));
      paint.setTextSize(fitFont(paint, text, l.width * scale, l.fontMm() * scale));
      paint.setTextAlign(
          l.align == 1 ? Paint.Align.CENTER : l.align == 2 ? Paint.Align.RIGHT : Paint.Align.LEFT);
      float x = (l.x + (l.align == 1 ? l.width / 2 : l.align == 2 ? l.width : 0)) * scale;
      float baseline = l.y * scale - paint.ascent();
      c.save();
      c.clipRect(
          l.x * scale,
          l.y * scale,
          (l.x + l.width) * scale,
          Math.min(20, l.y + l.boxHeight()) * scale);
      c.drawText(text, x, baseline, paint);
      c.restore();
      if (selected == i) {
        paint.setColor(0xFF215DE7);
        paint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        paint.setStyle(Paint.Style.STROKE);
        selectionBox.set(
            l.x * scale, l.y * scale, (l.x + l.width) * scale, (l.y + l.boxHeight()) * scale);
        c.drawRoundRect(selectionBox, 3, 3, paint);
        paint.setStyle(Paint.Style.FILL);
      }
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (onMove == null || template.lines.isEmpty()) return false;
    float scale = getWidth() / 30f;
    if (e.getAction() == MotionEvent.ACTION_DOWN) {
      for (int i = template.lines.size() - 1; i >= 0; i--) {
        LabelTemplate.Line l = template.lines.get(i);
        if (e.getX() / scale >= l.x
            && e.getX() / scale <= l.x + l.width
            && e.getY() / scale >= l.y
            && e.getY() / scale <= l.y + l.boxHeight()) {
          selected = i;
          startX = e.getX();
          startY = e.getY();
          originalX = l.x;
          originalY = l.y;
          getParent().requestDisallowInterceptTouchEvent(true);
          invalidate();
          return true;
        }
      }
      return false;
    }
    if (selected < 0) return false;
    if (e.getAction() == MotionEvent.ACTION_MOVE) {
      LabelTemplate.Line l = template.lines.get(selected);
      l.x = Math.max(0, Math.min(30 - l.width, originalX + (e.getX() - startX) / scale));
      l.y = Math.max(0, Math.min(20 - l.boxHeight(), originalY + (e.getY() - startY) / scale));
      invalidate();
      return true;
    }
    if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
      getParent().requestDisallowInterceptTouchEvent(false);
      if (e.getAction() == MotionEvent.ACTION_UP) {
        performClick();
        onMove.moved(selected);
      } else {
        LabelTemplate.Line line = template.lines.get(selected);
        line.x = originalX;
        line.y = originalY;
        invalidate();
      }
      return true;
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
