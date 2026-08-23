/*
 * Reach - what the detectors see, drawn over the camera view.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Detection boxes drawn on the camera view. The wearer never sees this - it separates "the
 * detector missed it" from "the detector found it and the logic dropped it", which you cannot tell apart any other way, and it is what a mirrored screen shows a room during judging.
 *
 * <p>Plain Canvas on a View above the GLSurfaceView. A rectangle is not worth a shader.
 */
public class DetectionOverlayView extends View {

  private static final int MAX_BOXES = 8;

  private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final RectF[] boxes = new RectF[MAX_BOXES];
  private int boxCount = 0;
  private final String[] boxLabels = new String[MAX_BOXES];
  private int highlighted = -1;

  // the cane, drawn. this is the single most legible thing on the mirrored screen: a sighted room
  // watches a line leave the wearer's finger and stop dead on the thing they're asking about
  private final Paint canePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint caneTipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint caneTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private boolean caneVisible = false;
  private boolean caneHit = false;
  private float caneStartX, caneStartY, caneEndX, caneEndY;
  private String caneLabel = "";

  public DetectionOverlayView(Context context, AttributeSet attrs) {
    super(context, attrs);
    for (int i = 0; i < MAX_BOXES; i++) {
      boxes[i] = new RectF();
    }
    boxPaint.setStyle(Paint.Style.STROKE);
    boxPaint.setStrokeWidth(7f);
    boxPaint.setColor(Color.parseColor("#FF5C4D"));
    labelPaint.setColor(Color.WHITE);
    labelPaint.setTextSize(38f);
    labelPaint.setFakeBoldText(true);
    plate.setColor(Color.parseColor("#FF5C4D"));

    canePaint.setStyle(Paint.Style.STROKE);
    canePaint.setStrokeWidth(9f);
    canePaint.setColor(Color.parseColor("#FF3B30"));
    // dashes rather than a solid line: a solid one reads as a laser, which is a claim we are not
    // making. this is an inference along a direction, and dashes look like what it is
    canePaint.setPathEffect(new DashPathEffect(new float[] {26f, 20f}, 0f));
    caneTipPaint.setStyle(Paint.Style.FILL);
    caneTipPaint.setColor(Color.parseColor("#FF3B30"));
    caneTextPaint.setColor(Color.WHITE);
    caneTextPaint.setTextSize(46f);
    caneTextPaint.setFakeBoldText(true);
  }

  /** @param hit false when the ray found nothing - we still draw the aim, just without a tip */
  public void setCane(
      float startX, float startY, float endX, float endY, boolean hit, String label) {
    caneVisible = true;
    caneHit = hit;
    caneStartX = startX;
    caneStartY = startY;
    caneEndX = endX;
    caneEndY = endY;
    caneLabel = label;
    postInvalidate();
  }

  public void clearCane() {
    if (caneVisible) {
      caneVisible = false;
      postInvalidate();
    }
  }

  /**
   * @param viewRects boxes already converted to this view's pixels; pass 0 to clear
   * @param highlighted index of the box the wearer is pointing at, or -1
   */
  public void setBoxes(float[] viewRects, String[] labels, int count, int highlighted) {
    boxCount = Math.min(count, MAX_BOXES);
    for (int i = 0; i < boxCount; i++) {
      boxes[i].set(
          viewRects[i * 4], viewRects[i * 4 + 1], viewRects[i * 4 + 2], viewRects[i * 4 + 3]);
      boxes[i].sort();
      boxLabels[i] = labels != null && labels[i] != null ? labels[i] : "?";
    }
    this.highlighted = highlighted;
    postInvalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (caneVisible) {
      canvas.drawLine(caneStartX, caneStartY, caneEndX, caneEndY, canePaint);
      if (caneHit) {
        canvas.drawCircle(caneEndX, caneEndY, 26f, caneTipPaint);
        float textWidth = caneTextPaint.measureText(caneLabel);
        canvas.drawRect(
            caneEndX - textWidth / 2f - 14f,
            caneEndY + 36f,
            caneEndX + textWidth / 2f + 14f,
            caneEndY + 94f,
            plate);
        canvas.drawText(caneLabel, caneEndX - textWidth / 2f, caneEndY + 80f, caneTextPaint);
      }
    }
    for (int i = 0; i < boxCount; i++) {
      RectF box = boxes[i];
      boolean isTarget = i == highlighted;
      // the one being pointed at is drawn thick; the rest are there so we can see what the
      // detector actually believes, which is the only way to tell a bad aim from a bad detection
      boxPaint.setStrokeWidth(isTarget ? 10f : 4f);
      boxPaint.setColor(isTarget ? Color.parseColor("#FF3B30") : Color.parseColor("#66D9FF"));
      canvas.drawRect(box, boxPaint);
      String text = boxLabels[i] == null ? "?" : boxLabels[i];
      float textWidth = labelPaint.measureText(text);
      float top = Math.max(box.top, 44f);
      plate.setColor(isTarget ? Color.parseColor("#FF3B30") : Color.parseColor("#1166D9FF"));
      canvas.drawRect(box.left, top - 40f, box.left + textWidth + 20f, top, plate);
      canvas.drawText(text, box.left + 10f, top - 10f, labelPaint);
    }
  }
}
