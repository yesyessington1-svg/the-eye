/*
 * Reach - ground level changes.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import java.util.Arrays;

/**
 * Steps up, and the thing canes are actually for: steps down. WeWALK's own docs say its obstacle
 * sensor cannot detect ground-level drops.
 *
 * <p>Compares two bands inside the same frame - the floor about one pace out against two paces
 * out. A step up makes the far band higher, a drop makes it lower or makes it vanish.
 *
 * <p>A rolling memory of the floor cannot detect a step up by construction: the lowest point of a
 * staircase is the ground at its base, which is the height it always was.
 */
public class TerrainWatch {

  /**
   * Off, after three rewrites.
   *
   * <p>v1 could not detect a step up by construction. v2's bands were always empty - the
   * floor does not enter frame closer than ~4m with the camera level. v3 announced STEP UP 11cm at
   * the edge of a rug, on 736 samples across 107 stable frames, having logged -76cm and -19cm on
   * the same flat floor. It passed every confidence gate because the readings were consistent.
   *
   * <p>A few hundred pixels at the bottom of a 160x90 image cannot answer "has the ground changed
   * by ten centimetres". A drop detector that cries wolf is worse than none.
   */
  public static final boolean ENABLED = false;


  /** what the floor is doing ahead */
  public enum State {
    LEVEL,
    STEP_UP,
    DROP,
    /** not enough of the floor is readable to say */
    UNKNOWN
  }

  // no fixed distance bands. The first version used 0.75-1.15 m and 1.35-1.90 m and logged zero
  // samples in the near band on every single frame, for a reason no threshold could fix: with the
  // camera level at forehead height the floor sits about 57 degrees below the optical axis at one
  // metre, and the depth image only covers about 20. The floor does not enter frame until roughly
  // four metres out. So we take whatever floor is actually visible, wherever it is, and split it in
  // half by distance. Tilt the phone down and the whole thing moves closer on its own
  private static final float HALF_WIDTH_M = 0.25f;

  // the two halves have to be far enough apart to be comparing different ground
  private static final float MIN_SPREAD_M = 0.6f;

  // 7cm is about half a kerb
  private static final float STEP_M = 0.07f;

  private static final int MIN_BAND_SAMPLES = 12;

  private State state = State.UNKNOWN;
  private float deviationMeters = 0f;
  private int lastNearCount = 0;
  private int lastFarCount = 0;

  private final float[] heights = new float[4096];
  private final float[] grounds = new float[4096];
  private final Integer[] order = new Integer[4096];

  /**
   * @param height how far below the wearer each floor sample sits, metres, negative
   * @param ground how far ahead along the ground each sample is, metres, same order
   * @param count how many entries are real
   */
  public void update(float[] height, float[] ground, int count) {
    if (count < MIN_BAND_SAMPLES * 2) {
      lastNearCount = count;
      lastFarCount = 0;
      state = State.UNKNOWN;
      return;
    }
    int used = Math.min(count, heights.length);
    Integer[] index = new Integer[used];
    for (int i = 0; i < used; i++) {
      heights[i] = height[i];
      grounds[i] = ground[i];
      index[i] = i;
    }
    Arrays.sort(index, (a, b) -> Float.compare(grounds[a], grounds[b]));

    int half = used / 2;
    float nearGround = grounds[index[half / 2]];
    float farGround = grounds[index[half + half / 2]];
    lastNearCount = half;
    lastFarCount = used - half;
    if (farGround - nearGround < MIN_SPREAD_M) {
      // all the visible floor is at one distance, so there is no near and far to compare
      state = State.UNKNOWN;
      return;
    }

    float near = medianHeight(index, 0, half);
    float far = medianHeight(index, half, used);
    deviationMeters = far - near;
    if (deviationMeters > STEP_M) {
      state = State.STEP_UP;
    } else if (deviationMeters < -STEP_M) {
      state = State.DROP;
    } else {
      state = State.LEVEL;
    }
  }

  private float medianHeight(Integer[] index, int from, int to) {
    int n = to - from;
    float[] window = new float[n];
    for (int i = 0; i < n; i++) {
      window[i] = heights[index[from + i]];
    }
    Arrays.sort(window);
    return window[n / 2];
  }

  public State state() {
    return state;
  }

  /** how far off level the far band is, in metres. positive is up */
  public float deviationMeters() {
    return deviationMeters;
  }

  public int nearBandCount() {
    return lastNearCount;
  }

  public int farBandCount() {
    return lastFarCount;
  }

  public static float halfWidthMeters() {
    return HALF_WIDTH_M;
  }

  public void reset() {
    state = State.UNKNOWN;
    deviationMeters = 0f;
  }
}
