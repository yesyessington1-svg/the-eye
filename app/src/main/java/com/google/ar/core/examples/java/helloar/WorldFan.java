/*
 * The Eye - what the room looks like from here, remembered in world bearings.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import java.util.Arrays;
import java.util.Locale;

/**
 * A polar map of the room around the wearer, indexed by compass bearing rather than by where the
 * camera happens to point.
 *
 * <p>Two measured problems, one cause. The camera sees 65 degrees; a logged run picked a heading
 * right at the rim of that on 21% of frames, which is the device recommending a direction it can
 * barely see, and once sent the wearer into a chair. And the chosen heading jumped more than 15
 * degrees between consecutive frames on 31 of 104, because a fan measured against the camera moves
 * when the head moves: turn 20 degrees and the same chair slides from +10 to -10, so "go left"
 * means something different every frame and no amount of hysteresis can hold it.
 *
 * <p>Both go away in world coordinates. A bearing here is a bearing in the room. Turning the head
 * changes which part of the map is being refreshed, not what the map says. And because the map
 * outlives the view, the wearer can be told about a gap the camera saw two seconds ago and is no
 * longer pointed at.
 *
 * <p>This is the local occupancy map every mobile robot keeps, minus the parts that need wheel
 * odometry: ARCore's pose supplies both the rotation and the translation.
 */
public class WorldFan {

  private static final int BINS = 120;
  private static final float BIN_DEG = 360f / BINS;

  /** anything older than this is a memory of a room that may have moved */
  private static final long FORGET_MS = 2500;

  /** a cell has to be seen this well before it is allowed to block a route */
  private static final float MIN_WEIGHT = 0.05f;

  private static final float MAX_RANGE_M = 4.0f;

  private final float[] range = new float[BINS];
  private final float[] weight = new float[BINS];
  private final long[] seenMs = new long[BINS];

  public WorldFan() {
    Arrays.fill(range, MAX_RANGE_M);
  }

  private static int binOf(float worldYawDeg) {
    float wrapped = worldYawDeg % 360f;
    if (wrapped < 0) {
      wrapped += 360f;
    }
    int bin = (int) (wrapped / BIN_DEG);
    return bin >= BINS ? BINS - 1 : bin;
  }

  /**
   * Record one return.
   *
   * @param worldYawDeg where it is in the room, not where it is in the picture
   * @param w how much the sample is worth, from the sensor's own confidence
   */
  public void observe(float worldYawDeg, float rangeM, float w, long nowMs) {
    if (rangeM <= 0f || rangeM > MAX_RANGE_M || w < MIN_WEIGHT) {
      return;
    }
    int bin = binOf(worldYawDeg);
    boolean stale = nowMs - seenMs[bin] > FORGET_MS;
    if (stale || rangeM < range[bin]) {
      range[bin] = rangeM;
      weight[bin] = w;
    } else {
      weight[bin] = Math.max(weight[bin], w);
    }
    seenMs[bin] = nowMs;
  }

  /**
   * Carry the map forward as the wearer walks.
   *
   * <p>Something at bearing θ gets closer by the component of the step along θ, so a step straight
   * ahead pulls the things in front nearer and leaves the things beside you alone. Without this the
   * map describes a room the wearer has already walked out of.
   */
  public void advance(float stepM, float headingYawDeg) {
    if (stepM <= 0.005f) {
      return;
    }
    for (int i = 0; i < BINS; i++) {
      if (range[i] >= MAX_RANGE_M) {
        continue;
      }
      double delta = Math.toRadians(i * BIN_DEG + BIN_DEG / 2f - headingYawDeg);
      range[i] -= stepM * (float) Math.cos(delta);
      if (range[i] < 0.1f || range[i] > MAX_RANGE_M) {
        range[i] = MAX_RANGE_M;
        weight[i] = 0f;
      }
    }
  }

  /** how far you could walk along a room bearing, MAX when nothing is known or remembered */
  public float freeDistanceAt(float worldYawDeg, long nowMs) {
    int bin = binOf(worldYawDeg);
    if (nowMs - seenMs[bin] > FORGET_MS || weight[bin] < MIN_WEIGHT) {
      return MAX_RANGE_M;
    }
    return range[bin];
  }

  /** true when this bearing has been looked at recently enough to be worth trusting */
  public boolean isKnown(float worldYawDeg, long nowMs) {
    return nowMs - seenMs[binOf(worldYawDeg)] <= FORGET_MS;
  }

  /** how much of the map around straight ahead is remembered, 0 to 1 */
  public float coverageAround(float headingYawDeg, float halfSpanDeg, long nowMs) {
    int span = Math.max(1, (int) (halfSpanDeg / BIN_DEG));
    int centre = binOf(headingYawDeg);
    int known = 0;
    for (int d = -span; d <= span; d++) {
      int bin = ((centre + d) % BINS + BINS) % BINS;
      if (nowMs - seenMs[bin] <= FORGET_MS) {
        known++;
      }
    }
    return known / (float) (2 * span + 1);
  }

  public String diagnostics(float headingYawDeg, long nowMs) {
    return String.format(
        Locale.US, "world cov=%.0f%%", coverageAround(headingYawDeg, 60f, nowMs) * 100f);
  }
}
