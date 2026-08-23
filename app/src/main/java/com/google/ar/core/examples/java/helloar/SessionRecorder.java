/*
 * The Eye - session recording, for looking at afterwards.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import android.util.Log;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * Writes one row per frame to a CSV, so a run can be looked at after it happened instead of
 * remembered.
 *
 * <p>Every fix in this project came out of a number in a log, and reading a log by eye stops
 * working somewhere around the thousandth line. This is the same data in a shape that plots:
 * where obstacles were, how far, which sense was talking, and when the sensor gave up.
 *
 * <p>Lands in the app's own external files directory, so `adb pull` gets it without root.
 */
public class SessionRecorder {

  private static final String TAG = "EyeRecorder";

  private static final String HEADER =
      "ms,mode,channel,state,distance_m,lateral_m,direction,label,"
          + "gap_fit,gap_bearing_deg,gap_width_m,coverage,floor_m,sight_m,self_points,speed_mps";

  // one row per frame at 30fps is 1800 rows a minute, which is fine for a CSV and far too much to
  // flush every time
  private static final int FLUSH_EVERY = 60;

  private BufferedWriter writer;
  private File file;
  private int sinceFlush = 0;
  private long startedMs = 0;

  public SessionRecorder(Context context, long startedMs) {
    this.startedMs = startedMs;
    try {
      File dir = new File(context.getExternalFilesDir(null), "sessions");
      if (!dir.exists() && !dir.mkdirs()) {
        Log.w(TAG, "could not make sessions directory");
        return;
      }
      file = new File(dir, "reach-" + startedMs + ".csv");
      writer = new BufferedWriter(new FileWriter(file));
      writer.write(HEADER);
      writer.newLine();
      Log.i(TAG, "EYE_RECORD writing " + file.getAbsolutePath());
    } catch (IOException e) {
      Log.w(TAG, "could not open session file", e);
      writer = null;
    }
  }

  public String path() {
    return file == null ? "none" : file.getAbsolutePath();
  }

  public void row(
      String mode,
      String channel,
      String state,
      float distanceM,
      float lateralM,
      String label,
      String gapFit,
      float gapBearingDeg,
      float gapWidthM,
      float coverage,
      float floorM,
      float sightM,
      int selfPoints,
      float speedMps) {
    if (writer == null) {
      return;
    }
    try {
      writer.write(
          String.format(
              Locale.US,
              "%d,%s,%s,%s,%.3f,%.3f,%s,%s,%s,%.1f,%.3f,%.3f,%.3f,%.2f,%d,%.3f",
              System.currentTimeMillis() - startedMs,
              mode,
              channel,
              state,
              distanceM,
              lateralM,
              lateralM < -0.12f ? "left" : lateralM > 0.12f ? "right" : "centre",
              label == null ? "" : label,
              gapFit,
              gapBearingDeg,
              gapWidthM,
              coverage,
              floorM,
              sightM,
              selfPoints,
              speedMps));
      writer.newLine();
      if (++sinceFlush >= FLUSH_EVERY) {
        sinceFlush = 0;
        writer.flush();
      }
    } catch (IOException e) {
      // a failed row is not worth killing the frame over
    }
  }

  public void close() {
    if (writer == null) {
      return;
    }
    try {
      writer.flush();
      writer.close();
      Log.i(TAG, "EYE_RECORD closed " + path());
    } catch (IOException e) {
      Log.w(TAG, "could not close session file", e);
    }
    writer = null;
  }
}
