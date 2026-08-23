/*
 * The Eye - evidence about the corridor, accumulated rather than thresholded.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import java.util.Arrays;
import java.util.Locale;

/**
 * A one-dimensional occupancy grid along the corridor, updated in log-odds.
 *
 * <p>The problem this solves is a lie the sensor tells consistently. Walking a clear corridor with
 * windows, depth-from-motion had 90% of its pixels in the lowest confidence band and reported a
 * surface at 0.7 to 1.2m on 61%, 91% and 99% of three consecutive frames. Nothing was there. A
 * median across frames cannot help, because every frame agrees; the reading is stable and wrong.
 *
 * <p>Moravec and Elfes (1985) is the standard answer, and the part that matters is the half people
 * forget. A range reading at distance d is not only evidence that something sits at d. It is
 * evidence that <b>everything in front of d is empty</b>, because the ray got there. So a frame
 * that reads 2.0m actively cancels the false evidence at 0.9m rather than merely outvoting it, and
 * a phantom has to be believed by nearly every frame to survive.
 *
 * <p>Cells are shifted by the wearer's own measured motion each frame, so evidence gathered three
 * steps ago is still in the right place, and old evidence decays so a room that changes is
 * eventually believed.
 */
public class OccupancyBeam {

  private static final float MIN_M = 0.30f;
  private static final float MAX_M = 4.00f;
  private static final float BIN_M = 0.10f;
  private static final int BINS = (int) ((MAX_M - MIN_M) / BIN_M);

  /** evidence added to the cell a return landed in */
  private static final float L_OCCUPIED = 0.85f;

  /** evidence added to every cell the ray passed through on the way. negative: this is emptiness */
  private static final float L_FREE = -0.30f;

  /** log-odds are clamped so no cell can become unarguable and stop responding to new evidence */
  private static final float L_LIMIT = 4.0f;

  /** believe a cell is occupied above this. roughly 85% posterior */
  private static final float L_THRESHOLD = 1.75f;

  /** per-frame forgetting, so a corridor that changes is eventually believed */
  private static final float DECAY = 0.96f;

  private final float[] logOdds = new float[BINS];
  private final float[] shifted = new float[BINS];
  private int observationsThisFrame = 0;

  /**
   * Start a frame.
   *
   * @param movedMetres how far the wearer travelled since the last frame. Everything in the world
   *     got this much closer, so the evidence has to move with it or it describes where the room
   *     used to be.
   */
  public void beginFrame(float movedMetres) {
    observationsThisFrame = 0;
    for (int i = 0; i < BINS; i++) {
      logOdds[i] *= DECAY;
    }
    if (movedMetres <= 0.005f) {
      return;
    }
    float shiftBins = movedMetres / BIN_M;
    Arrays.fill(shifted, 0f);
    for (int i = 0; i < BINS; i++) {
      float from = i + shiftBins;
      int lo = (int) Math.floor(from);
      float frac = from - lo;
      // linear interpolation, because a step of 7cm is most of a cell and rounding it away would
      // smear the evidence backwards over a few seconds of walking
      if (lo >= 0 && lo < BINS) {
        shifted[i] += logOdds[lo] * (1f - frac);
      }
      if (lo + 1 >= 0 && lo + 1 < BINS) {
        shifted[i] += logOdds[lo + 1] * frac;
      }
    }
    System.arraycopy(shifted, 0, logOdds, 0, BINS);
  }

  /**
   * One depth return.
   *
   * @param rangeM how far the return was
   * @param weight 0 to 1, how much this sample is worth. Raw depth carries a confidence plane;
   *     smoothed depth has none and is worth less because it fills its own holes by guessing.
   */
  public void observe(float rangeM, float weight) {
    if (rangeM < MIN_M || rangeM > MAX_M || weight <= 0f) {
      return;
    }
    observationsThisFrame++;
    int hit = (int) ((rangeM - MIN_M) / BIN_M);
    if (hit >= BINS) {
      hit = BINS - 1;
    }
    // the ray reached the hit, so everything before it is evidence of empty space
    for (int i = 0; i < hit; i++) {
      logOdds[i] = clamp(logOdds[i] + L_FREE * weight);
    }
    logOdds[hit] = clamp(logOdds[hit] + L_OCCUPIED * weight);
  }

  private static float clamp(float value) {
    return Math.max(-L_LIMIT, Math.min(L_LIMIT, value));
  }

  /** distance to the nearest cell the evidence supports, or NaN when nothing is believed */
  public float nearestOccupied() {
    for (int i = 0; i < BINS; i++) {
      if (logOdds[i] > L_THRESHOLD) {
        return MIN_M + (i + 0.5f) * BIN_M;
      }
    }
    return Float.NaN;
  }

  /** how strongly the evidence supports something at this distance, in log-odds */
  public float supportAt(float metres) {
    if (Float.isNaN(metres) || metres < MIN_M || metres > MAX_M) {
      return 0f;
    }
    int bin = (int) ((metres - MIN_M) / BIN_M);
    if (bin >= BINS) {
      bin = BINS - 1;
    }
    // a neighbour counts: the corridor's percentile and the grid's cell edges will not agree to
    // the centimetre, and demanding they do would throw away real obstacles
    float best = logOdds[bin];
    if (bin > 0) {
      best = Math.max(best, logOdds[bin - 1]);
    }
    if (bin + 1 < BINS) {
      best = Math.max(best, logOdds[bin + 1]);
    }
    return best;
  }

  /** enough evidence to be worth asking, i.e. the grid has actually seen some of this frame */
  public boolean hasEvidence() {
    return observationsThisFrame >= 8;
  }

  public String diagnostics() {
    float nearest = nearestOccupied();
    return String.format(
        Locale.US, "occ n=%d nearest=%s", observationsThisFrame,
        Float.isNaN(nearest) ? "-" : String.format(Locale.US, "%.2fm", nearest));
  }
}
