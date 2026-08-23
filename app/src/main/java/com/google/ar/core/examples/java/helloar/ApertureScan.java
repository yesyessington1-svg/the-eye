/*
 * The Eye - aperture scan: free-space encoding.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import java.util.Arrays;

/**
 * Free-space encoding: where is there room, rather than what is in the way.
 *
 * <p>Collapses the depth image into a fan of free distances, finds the widest run of open
 * directions, and reports its width against shoulder width.
 *
 * <p>Sources: Neugebauer 2020 (doi:10.1371/journal.pone.0237344) - obstacle encoding on this exact
 * hardware lost to a white cane. Virtual Whiskers (arXiv:2408.14550) - free-space encoding cut cane
 * contacts 70-80%. Frontiers in ICT 2017 (doi:10.3389/fict.2017.00023) - two coded parameters is
 * the ceiling while walking, hence no third channel.
 */
public class ApertureScan {

  public enum Fit {
    /** wide enough to walk straight through */
    WALK,
    /** you fit, but you have to turn your shoulders */
    SQUEEZE,
    /** nothing in view is wide enough for a body */
    BLOCKED,
    /** not enough depth to say anything, which is a thing we say out loud */
    UNKNOWN
  }

  // 1.5 deg. any wall pixel closes a whole bin, so 2.5 deg reported a 55cm gap as 44cm
  // ~1800 slab samples per frame is still ~44 per bin at this width
  private static final float BIN_DEG = 1.5f;
  private static final int BINS = 41;
  private static final int CENTRE_BIN = BINS / 2;
  private static final float HALF_SPAN_DEG = BINS * BIN_DEG / 2f;

  // the body slab. top clears door frames and low signage, bottom stops well above the floor -
  // the camera rides at forehead height so the floor sits around -1.5m, and a slab that reaches
  // it would report the ground as a wall in every direction, forever
  private static final float SLAB_TOP_M = 0.20f;
  // set every frame by GuardianCorridor from the floor it actually measured. a fixed offset from
  // the camera put this 45cm above the ground, which meant the fan cheerfully reported a walkable
  // gap through a row of bottles it could not see
  private float slabBottom = -1.10f;

  public void setSlabBottom(float metresRelativeToCamera) {
    slabBottom = metresRelativeToCamera;
  }

  // below this is a hand over the lens, above this ARCore's depth-from-motion is guessing
  private static final float MIN_RANGE_M = 0.35f;

  // Below this there is no route to give, only a stop, and that is the corridor's job.
  //
  // Dilation makes a close return catastrophic: something at 0.45m blocks asin(0.30/0.45) = 42
  // degrees, which is the whole fan. A logged run had the occupancy grid believing something at
  // 0.45 to 0.75m on most frames - a knee, most likely - and the fan answered BLOCKED on 58% of
  // them, so the wearer was never offered a way round anything.
  private static final float ROUTING_MIN_M = 0.60f;
  private static final float MAX_RANGE_M = 4.00f;

  // open = reaches this much past the nearest barrier. relative, not absolute: a fixed 2m
  // threshold made a doorway at 2.5m vanish because the whole fan cleared it
  // 0.60m is one body depth - through it, not alongside it
  private static final float OPEN_MARGIN_M = 0.60f;

  // if the nearest barrier anywhere in the fan is this far off, there is nothing to route around
  // yet and the honest answer is simply "clear". a doorway at three metres is still worth naming,
  // so this sits above that and below the scan limit
  private static final float CLEAR_M = 3.50f;

  // adult shoulder breadth. de Paz 2019 (doi:10.1371/journal.pone.0213342): blind walkers judge
  // apertures against their own shoulders and start turning at a ratio of about 1.22
  private static final float SHOULDER_M = 0.45f;

  // half a shoulder plus a hand's width of margin. this is the radius every obstacle is grown by
  private static final float BODY_RADIUS_M = 0.30f;

  // a heading has to get you at least this far to be worth turning towards
  private static final float USEFUL_HEADROOM_M = 1.60f;

  // shoulders turned. de Paz measured the ratio where blind walkers start doing this at 1.22, so
  // this is the same body at the width it presents side-on
  private static final float SQUEEZE_RADIUS_M = 0.20f;

  // VFH+ cost weights. straightness dominates, hysteresis breaks ties, headroom is a tiebreak on
  // the tiebreak. tuned so a 10 degree turn is worth about half a metre of extra headroom
  // All three are in degrees-equivalent, so they can be compared honestly.
  //
  // The first version weighted headroom at 4.0 per metre, which peaks at 16 against a maximum
  // straightness cost of 30 - so the choice was driven by which direction had marginally more room,
  // and that varies with noise every frame. The heading then jumped more than 15 degrees on 41% of
  // frames. Headroom is now bucketed to half a metre, so noise inside a bucket changes nothing,
  // and changing your mind costs more than going slightly off-straight.
  private static final float COST_STRAIGHT = 1.0f;
  private static final float COST_HYSTERESIS = 1.6f;
  private static final float COST_HEADROOM_PER_BUCKET = 2.0f;
  private static final float HEADROOM_BUCKET_M = 0.5f;
  private static final float RATIO_WALK = 1.22f;
  private static final float RATIO_SQUEEZE = 1.00f;

  // how much of the fan an edge-touching run has to occupy before we treat it as a real route out
  // rather than a couple of noisy bins at the rim. four bins
  private static final float EDGE_MIN_SPAN_DEG = 6.0f;

  // straight ahead, for the "just keep walking" case. narrow: this is the cone your head
  // is already pointed down, not the whole scene
  private static final float AHEAD_DEG = 6.0f;

  // one hot pixel must not build a wall. we keep the three nearest returns per bin and believe the
  // third, so it takes three independent pixels agreeing to close a direction off
  private static final int MIN_BIN_HITS = 3;

  // same reasoning as the corridor debounce: a category flip has to survive three frames before the
  // user hears about it, or the display chatters at every doorway edge
  private static final int DEBOUNCE_FRAMES = 3;
  private static final int HISTORY = 9;

  public static final class Gap {
    public final Fit fit;
    /** degrees off straight ahead; negative left, positive right */
    public final float bearingDeg;
    /** metres between the two things that bound the gap */
    public final float widthM;
    /** how far you could walk straight forward before hitting something */
    public final float clearAheadM;
    /** the gap runs off the edge of what the camera can see, so widthM is a lower bound */
    public final boolean openEdge;
    /** fraction of the fan that had any depth at all - our own confidence, reported honestly */
    public final float coverage;
    /** distance to the nearest barrier anywhere in the fan; large means nothing to route around */
    public final float barrierM;

    Gap(Fit fit, float bearingDeg, float widthM, float clearAheadM, boolean openEdge,
        float coverage, float barrierM) {
      this.fit = fit;
      this.bearingDeg = bearingDeg;
      this.widthM = widthM;
      this.clearAheadM = clearAheadM;
      this.openEdge = openEdge;
      this.coverage = coverage;
      this.barrierM = barrierM;
    }
  }

  // three nearest returns per bin, metres. min3 is the one we trust
  private final float[] min1 = new float[BINS];
  private final float[] min2 = new float[BINS];
  private final float[] min3 = new float[BINS];

  // seen[] = any return at all in this direction, at any height. that is coverage
  // slabHits[] = returns at body height, close enough to matter. only these close a bin off
  // conflating the two cost more than half the fan
  private final int[] seen = new int[BINS];
  private final int[] slabHits = new int[BINS];

  // the free-distance profile after every obstacle has been grown by the body's half width.
  // dilated[i] is how far you could walk along bearing i WITH SHOULDERS ON, not as a point
  private final float[] dilated = new float[BINS];

  private final float[] bearingHistory = new float[HISTORY];
  private final float[] widthHistory = new float[HISTORY];
  private int historyCount = 0;
  private int historyIndex = 0;

  private Fit stableFit = Fit.UNKNOWN;
  private Fit candidateFit = Fit.UNKNOWN;
  private int candidateFrames = 0;

  private Gap lastGap = new Gap(Fit.UNKNOWN, 0f, 0f, 0f, false, 0f, MAX_RANGE_M);

  /** the free-distance profile from the last finish(), metres per bin, for the HUD to draw */
  public float[] profile() {
    return min3;
  }

  public static int bins() {
    return BINS;
  }

  public static float binDegrees() {
    return BIN_DEG;
  }

  public Gap lastGap() {
    return lastGap;
  }

  /** call once per depth frame before feeding points */
  public void begin() {
    Arrays.fill(min1, Float.MAX_VALUE);
    Arrays.fill(min2, Float.MAX_VALUE);
    Arrays.fill(min3, Float.MAX_VALUE);
    Arrays.fill(seen, 0);
    Arrays.fill(slabHits, 0);
  }

  /**
   * Feed one depth point, already expressed against gravity by the caller.
   *
   * @param lateralM sideways offset from the walking line, metres, positive right
   * @param aboveCameraM height along world-up relative to the camera, metres
   * @param groundM horizontal distance from the camera, metres
   */
  public void add(float lateralM, float aboveCameraM, float groundM) {
    if (groundM < MIN_RANGE_M) {
      return;
    }
    float ratio = lateralM / groundM;
    if (ratio < -1f) {
      ratio = -1f;
    } else if (ratio > 1f) {
      ratio = 1f;
    }
    float bearingDeg = (float) Math.toDegrees(Math.asin(ratio));
    int bin = (int) ((bearingDeg + HALF_SPAN_DEG) / BIN_DEG);
    if (bin < 0 || bin >= BINS) {
      return;
    }
    // a point beyond the scan range is still evidence that the sensor is working in this
    // direction, and it must count towards coverage - otherwise standing in a large room, where
    // every surface is far away, reads as "no depth at all" and the device says it is blind
    seen[bin]++;

    // floor returns count too. visible floor out to 3m proves nothing is standing there -
    // anything standing there would have hidden it. positively observed free space
    // discarding them before this line put coverage at a median of 37% while tilted at a floor
    // the sensor could see perfectly
    if (aboveCameraM > SLAB_TOP_M || aboveCameraM < slabBottom) {
      return;
    }
    if (groundM > MAX_RANGE_M) {
      return;
    }
    slabHits[bin]++;
    // insertion into a three-deep sorted list. cheaper than it looks and it runs once per pixel
    if (groundM < min1[bin]) {
      min3[bin] = min2[bin];
      min2[bin] = min1[bin];
      min1[bin] = groundM;
    } else if (groundM < min2[bin]) {
      min3[bin] = min2[bin];
      min2[bin] = groundM;
    } else if (groundM < min3[bin]) {
      min3[bin] = groundM;
    }
  }

  /** the distance we are willing to claim for a bin: MAX_RANGE when nothing credible is in it */
  /** the distance we use for ROUTING, which ignores anything too close to walk around */
  private float routingDistance(int bin) {
    float d = freeDistance(bin);
    if (d >= MAX_RANGE_M && world != null) {
      // nothing in this frame, but the room map may remember it from when the head was turned.
      // this is what widens the usable fan past the 65 degrees the sensor can see at any instant
      float remembered = world.freeDistanceAt(worldYawOfBin(bin), worldNowMs);
      if (remembered < d) {
        d = remembered;
      }
    }
    return d < ROUTING_MIN_M ? MAX_RANGE_M : d;
  }

  private float freeDistance(int bin) {
    if (slabHits[bin] < MIN_BIN_HITS || min3[bin] == Float.MAX_VALUE) {
      // either genuinely empty out to the scan limit, or too few returns to close it off. either
      // way we are not entitled to call it blocked on this evidence
      return MAX_RANGE_M;
    }
    return min3[bin];
  }

  /**
   * Grow every obstacle by half a body, so a direction that survives is one you actually fit
   * through.
   *
   * <p>Configuration space, from Lozano-Perez, and the step Borenstein and Koren's VFH does before
   * choosing a heading. We were measuring how wide a gap was in metres and comparing it to a
   * shoulder afterwards, which answers a question about the gap. The useful question is about the
   * body: which bearings can it pass along. A logged run in a cluttered room reported a median gap
   * width of 0.23 m against a 0.45 m shoulder, so the advice was always "narrow" and never a
   * direction.
   *
   * <p>An obstacle at distance d blocks every bearing within asin(r/d) of itself, because at that
   * angle your shoulder is still inside it. Close obstacles therefore block a wide arc and distant
   * ones a narrow one, which is exactly right and is not something a width comparison can express.
   */
  private void dilate(float radiusM) {
    Arrays.fill(dilated, MAX_RANGE_M);
    for (int i = 0; i < BINS; i++) {
      float d = routingDistance(i);
      if (d >= MAX_RANGE_M) {
        continue;
      }
      float ratio = radiusM / Math.max(d, 0.25f);
      float halfAngleDeg =
          ratio >= 1f ? HALF_SPAN_DEG : (float) Math.toDegrees(Math.asin(ratio));
      int spread = (int) Math.ceil(halfAngleDeg / BIN_DEG);
      int from = Math.max(0, i - spread);
      int to = Math.min(BINS - 1, i + spread);
      for (int j = from; j <= to; j++) {
        dilated[j] = Math.min(dilated[j], d);
      }
    }
  }

  /**
   * The bearing that gets you furthest, preferring straight ahead among equals.
   *
   * @return degrees off centre, or NaN when nothing is passable
   */
  private float bestHeading(float needMetres) {
    float best = Float.NaN;
    float bestCost = Float.MAX_VALUE;
    for (int i = 0; i < BINS; i++) {
      if (dilated[i] < needMetres) {
        continue;
      }
      float bearing = (i - CENTRE_BIN) * BIN_DEG;
      float cost = COST_STRAIGHT * Math.abs(bearing);
      if (!Float.isNaN(chosenWorldYaw)) {
        // Changing your mind has a price.
        //
        // Without this the fan flapped: 32 WALK, 25 BLOCKED and 21 SQUEEZE across 79 frames of the
        // same two backpacks, with the bearing swinging from -30 to +28. Two routes either side of
        // an obstacle score almost identically, so noise picks the winner and the wearer is told
        // to go left, then right, then left. This is the hysteresis term from VFH+ (Borenstein &
        // Koren 1998): a candidate has to beat the previous choice by a margin, not merely tie it.
        float drift = worldYawOfBin(i) - chosenWorldYaw;
        while (drift > 180f) {
          drift -= 360f;
        }
        while (drift < -180f) {
          drift += 360f;
        }
        cost += COST_HYSTERESIS * Math.abs(drift);
      }
      // room to spare is a tiebreak, not the decision. bucketed so a few centimetres of sensor
      // noise cannot make one direction beat another
      int buckets = (int) (Math.min(dilated[i], MAX_RANGE_M) / HEADROOM_BUCKET_M);
      cost -= COST_HEADROOM_PER_BUCKET * buckets;
      if (cost < bestCost) {
        bestCost = cost;
        best = bearing;
      }
    }
    return best;
  }

  /**
   * The heading we recommended last frame, kept as a ROOM bearing rather than a head bearing.
   *
   * <p>Stored camera-relative it was useless: turn the head 20 degrees and last frame's choice
   * points somewhere else, so the hysteresis term compared two different places and the advice
   * still jumped 15 degrees on 30% of frames.
   */
  private float chosenWorldYaw = Float.NaN;

  private WorldFan world = null;
  private float cameraYawDeg = 0f;
  private long worldNowMs = 0;

  /** hand in the room map and where the head is pointing in it */
  public void setWorldContext(WorldFan fan, float yawDeg, long nowMs) {
    this.world = fan;
    this.cameraYawDeg = yawDeg;
    this.worldNowMs = nowMs;
  }

  private float worldYawOfBin(int bin) {
    return cameraYawDeg + (bin - CENTRE_BIN) * BIN_DEG;
  }

  /**
   * How wide the passable arc around a heading is, in metres at the pinch distance. For the HUD
   * only: the decision was already made in configuration space, this is just something a person
   * watching a screen can read.
   */
  private float widthAlong(float bearingDeg, float need) {
    int centre = Math.round(bearingDeg / BIN_DEG) + CENTRE_BIN;
    if (centre < 0 || centre >= BINS) {
      return 0f;
    }
    int left = centre;
    while (left > 0 && dilated[left - 1] >= need) {
      left--;
    }
    int right = centre;
    while (right < BINS - 1 && dilated[right + 1] >= need) {
      right++;
    }
    float spanDeg = (right - left + 1) * BIN_DEG;
    // measured at the barrier, which is the pinch you actually pass through
    float pinch = Math.max(0.4f, Math.min(need - OPEN_MARGIN_M, MAX_RANGE_M));
    return 2f * pinch * (float) Math.tan(Math.toRadians(spanDeg / 2f));
  }

  /** how far along a dilated bearing you can walk */
  public float headroomAt(float bearingDeg) {
    int bin = Math.round(bearingDeg / BIN_DEG) + CENTRE_BIN;
    if (bin < 0 || bin >= BINS) {
      return 0f;
    }
    return dilated[bin];
  }

  /** call after the last add() of the frame */
  public Gap finish() {
    int covered = 0;
    for (int i = 0; i < BINS; i++) {
      if (seen[i] > 0) {
        covered++;
      }
    }
    float coverage = covered / (float) BINS;

    // how far straight ahead, over the narrow cone the head is actually pointed down
    float clearAhead = MAX_RANGE_M;
    int aheadSpan = Math.max(1, (int) (AHEAD_DEG / BIN_DEG));
    for (int i = CENTRE_BIN - aheadSpan; i <= CENTRE_BIN + aheadSpan; i++) {
      if (i >= 0 && i < BINS) {
        clearAhead = Math.min(clearAhead, freeDistance(i));
      }
    }

    if (coverage < 0.35f) {
      // less than a third of the fan has any depth. this is the sensor failing, not a blocked
      // world, and the two must never sound the same
      return settle(Fit.UNKNOWN, 0f, 0f, clearAhead, false, coverage, MAX_RANGE_M);
    }

    // the nearest barrier anywhere in the fan. everything below is measured against this, because
    // "open" only means anything relative to the thing you are trying to get past
    float barrier = MAX_RANGE_M;
    for (int i = 0; i < BINS; i++) {
      barrier = Math.min(barrier, routingDistance(i));
    }
    if (barrier >= CLEAR_M) {
      // nothing near enough to route around. saying "gap, two metres forty" about an empty room is
      // technically true and practically noise
      return settle(Fit.WALK, 0f, 0f, clearAhead, false, coverage, barrier);
    }
    // Configuration space first. Grow every obstacle by half a body, then any bearing still open
    // is one the wearer fits along - no width to measure and no ratio to compare afterwards.
    // a heading is only useful if it gets you PAST the thing in your way, not merely as far as it
    float need = barrier + OPEN_MARGIN_M;
    dilate(BODY_RADIUS_M);
    float heading = bestHeading(need);
    if (!Float.isNaN(heading)) {
      // a heading right on the rim is us saying "go that way" about a direction we can barely see.
      // flagged, so describe() says where rather than pretending to know how much room is there
      boolean rim = Math.abs(heading) >= HALF_SPAN_DEG - BIN_DEG * 1.5f;
      return settle(
          Fit.WALK, heading, widthAlong(heading, need), clearAhead, rim, coverage, barrier);
    }
    // nothing fits square on. try again with shoulders turned, which is a real thing people do
    dilate(SQUEEZE_RADIUS_M);
    heading = bestHeading(need);
    if (!Float.isNaN(heading)) {
      boolean rim = Math.abs(heading) >= HALF_SPAN_DEG - BIN_DEG * 1.5f;
      return settle(
          Fit.SQUEEZE, heading, widthAlong(heading, need), clearAhead, rim, coverage, barrier);
    }

    float openAt = barrier + OPEN_MARGIN_M;

    // walk the fan and pull out maximal runs of directions that get past the barrier
    Fit bestFit = Fit.BLOCKED;
    float bestBearing = 0f;
    float bestWidth = 0f;
    boolean bestEdge = false;

    int runStart = -1;
    for (int i = 0; i <= BINS; i++) {
      boolean open = i < BINS && freeDistance(i) >= openAt;
      if (open && runStart < 0) {
        runStart = i;
      } else if (!open && runStart >= 0) {
        int runEnd = i - 1;

        // the physical width of the gap is set by the two things that bound it, measured at
        // whichever of them is nearer - that is the pinch point you actually have to fit through.
        // a doorway read at the far jamb's distance would be reported wider than it is
        float leftBound = runStart > 0 ? freeDistance(runStart - 1) : barrier;
        float rightBound = runEnd < BINS - 1 ? freeDistance(runEnd + 1) : barrier;
        float pinch = Math.min(leftBound, rightBound);
        if (pinch < 0.40f) {
          pinch = 0.40f;
        } else if (pinch > MAX_RANGE_M) {
          pinch = MAX_RANGE_M;
        }

        // half a bin of credit at each end. the true edge of the opening lies somewhere inside the
        // first closed bin, uniformly, so counting only the fully-open bins under-reports every gap
        // by about one bin-width on average. this removes the bias without inventing width
        float spanDeg = (runEnd - runStart + 1.5f) * BIN_DEG;
        float widthM = 2f * pinch * (float) Math.tan(Math.toRadians(spanDeg / 2f));
        boolean edge = runStart == 0 || runEnd == BINS - 1;

        float ratio = widthM / SHOULDER_M;
        Fit fit;
        if (ratio >= RATIO_WALK) {
          fit = Fit.WALK;
        } else if (ratio >= RATIO_SQUEEZE) {
          fit = Fit.SQUEEZE;
        } else {
          fit = Fit.BLOCKED;
        }

        // a run reaching the fan edge does not end there - the sensor stops at 31 deg, the world
        // does not. widthM is a lower bound, so "too narrow" is not a claim we can make
        // without this, one 30cm bottle at 1m in an empty room read BLOCKED and buzzed flat out
        if (fit == Fit.BLOCKED && edge && spanDeg >= EDGE_MIN_SPAN_DEG) {
          fit = Fit.SQUEEZE;
        }

        if (fit != Fit.BLOCKED) {
          float bearing = ((runStart + runEnd) / 2f - CENTRE_BIN) * BIN_DEG;
          // prefer a gap you can walk through over one you have to squeeze into, and among equals
          // prefer the one nearest straight ahead. turning is a cost, and the wearer pays it
          boolean better =
              bestFit == Fit.BLOCKED
                  || (fit == Fit.WALK && bestFit == Fit.SQUEEZE)
                  || (fit == bestFit && Math.abs(bearing) < Math.abs(bestBearing));
          if (better) {
            bestFit = fit;
            bestBearing = bearing;
            bestWidth = widthM;
            bestEdge = edge;
          }
        }
        runStart = -1;
      }
    }

    return settle(bestFit, bestBearing, bestWidth, clearAhead, bestEdge, coverage, barrier);
  }

  /** median the last few frames and debounce. garbage does not repeat; a real doorway sits still */
  private Gap settle(
      Fit raw,
      float bearing,
      float width,
      float clearAhead,
      boolean edge,
      float coverage,
      float barrier) {
    if (raw == Fit.WALK || raw == Fit.SQUEEZE) {
      bearingHistory[historyIndex] = bearing;
      widthHistory[historyIndex] = width;
      historyIndex = (historyIndex + 1) % HISTORY;
      if (historyCount < HISTORY) {
        historyCount++;
      }
      float[] b = Arrays.copyOf(bearingHistory, historyCount);
      float[] w = Arrays.copyOf(widthHistory, historyCount);
      Arrays.sort(b);
      Arrays.sort(w);
      bearing = b[historyCount / 2];
      width = w[historyCount / 2];
    } else {
      historyCount = 0;
      historyIndex = 0;
    }

    if (raw == candidateFit) {
      candidateFrames++;
    } else {
      candidateFit = raw;
      candidateFrames = 1;
    }
    if (candidateFrames >= DEBOUNCE_FRAMES) {
      stableFit = candidateFit;
    }

    if (stableFit == Fit.WALK || stableFit == Fit.SQUEEZE) {
      chosenWorldYaw = cameraYawDeg + bearing;
    } else {
      // blocked or blind: no route to be loyal to, so the next one starts from straight ahead
      chosenWorldYaw = Float.NaN;
    }
    lastGap = new Gap(stableFit, bearing, width, clearAhead, edge, coverage, barrier);
    return lastGap;
  }

  /** what the wearer hears. short, because it is spoken while walking */
  /**
   * What the wearer hears: an action, not a measurement. Nobody turns 13 degrees, so the numbers
   * stay on the HUD and the ear gets a direction word.
   */
  public static String describe(Gap gap) {
    switch (gap.fit) {
      case WALK:
        if (gap.barrierM >= CLEAR_M || gap.widthM <= 0f) {
          return "clear";
        }
        if (gap.openEdge) {
          // the way round runs off the edge of the fan, so name the direction and claim nothing else
          return "round to your " + (gap.bearingDeg < 0 ? "left" : "right");
        }
        return "gap " + bearingWords(gap.bearingDeg);
      case SQUEEZE:
        if (gap.openEdge) {
          return "space " + bearingWords(gap.bearingDeg);
        }
        return Math.abs(gap.bearingDeg) <= AHEAD_DEG
            ? "narrow, turn sideways"
            : "narrow " + bearingWords(gap.bearingDeg);
      case BLOCKED:
        return "blocked";
      default:
        return "can't read the space";
    }
  }

  /** three buckets, because a walking person can act on "left" and cannot act on "13 degrees" */
  private static String bearingWords(float bearingDeg) {
    if (Math.abs(bearingDeg) <= AHEAD_DEG) {
      return "ahead";
    }
    String side = bearingDeg < 0 ? "left" : "right";
    return Math.abs(bearingDeg) < 20f ? "slightly " + side : side;
  }
}
