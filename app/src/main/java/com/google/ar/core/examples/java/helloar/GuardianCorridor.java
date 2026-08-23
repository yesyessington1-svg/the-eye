/*
 * The Eye - Guardian corridor filter.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.media.Image;
import com.google.ar.core.CameraIntrinsics;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Turns a depth image into a warning.
 *
 * <p>Keeps only the samples inside a box shaped like the space the wearer is about to walk
 * through, and reports the nearest thing in it. "Something is 1.2m away somewhere in frame" is
 * true nearly always and useful nearly never.
 */
public class GuardianCorridor {

  public enum State {
    CLEAR,
    HAZARD,
    /** something is in the corridor but nearer than depth we're willing to believe */
    BLIND,
    /** ARCore is handing us the same depth image over and over - we are reading a corpse */
    STALE
  }

  /**
   * Why we cannot see, when we cannot see. A dead sensor reads as an empty corridor, which the
   * wearer hears as "clear" - the most dangerous default a mobility aid can have.
   */
  public enum Blindness {
    NONE,
    /** the wearer is moving faster than depth-from-motion can keep up with */
    TOO_FAST,
    /** the near band is full of samples that don't agree with each other - the sensor is coming apart */
    INCOHERENT,
    /** consecutive frames can't agree where the obstacle is, so we have noise with a number on it */
    UNSTABLE,
    /** ARCore is serving the same depth image over and over */
    FROZEN
  }

  /** half the corridor width - shoulders are ~22cm each side, the rest is walking-line slop */
  private static final float HALF_WIDTH_M = 0.30f;

  // camera sits on the forehead, so y=0 is eye level. 20cm above catches door frames and hanging
  // signs; we stop at hip height so the floor, which is ~1.5m below the camera, never enters the
  // corridor and reports itself as an obstacle every single frame
  private static final float TOP_M = 0.20f;

  // anchored to the measured floor, not to the wearer's head
  // a fixed -0.90m from a forehead camera put the corridor floor 65cm above the ground, which made
  // bottles and bags structurally invisible rather than merely missed
  // 15cm of clearance: above floor noise, below a 25cm bottle
  private static final float FLOOR_CLEARANCE_M = 0.15f;
  // used only until enough of the floor has been seen. deeper than the old fixed value, because
  // being wrong in the tall direction costs a false alarm and being wrong in the short direction
  // costs a collision
  private static final float BOTTOM_FALLBACK_M = -1.15f;
  private static final float BOTTOM_LIMIT_M = -1.60f;
  // ceiling on the corridor's bottom edge - anything shallower is a table top or a bad frame
  private static final float BOTTOM_CEILING_M = -0.90f;

  // the wearer's own legs. tilt the phone down to see the floor and they enter the frame - one
  // logged walk reported 0.36m of "clear space" in an empty room, which was a shin
  // they sit almost directly below the camera, 10-35cm of horizontal distance; further out is the
  // world. 0.70 was too generous and ate a suitcase 65cm ahead of a tilted phone
  private static final float SELF_GROUND_M = 0.45f;
  private static final float SELF_HEIGHT_M = -0.40f;

  // nothing usable sits this close to a body-worn camera - it is chest, arm or hand
  // measured: frames flagged INCOHERENT carry 2077 self points and a 0.31m nearest return,
  // every other frame 33 and 0.95m
  private static final float SELF_RANGE_M = 0.35f;

  // where floor samples are collected from. independent of the corridor's own bottom
  // edge, which now moves - if the two were the same number they would chase each other
  private static final float FLOOR_BAND_M = -1.05f;

  // wider than the corridor. this strip used to be shared with TerrainWatch and inherited its
  // 25cm half-width, which starved the floor estimate - it fell back to the fixed default on most
  // frames. Terrain is switched off now, so the strip exists only to locate the ground, and more
  // of the ground is strictly better for that
  private static final float FLOOR_SAMPLE_HALF_WIDTH_M = 0.60f;

  // 0.60m is where our own bring-up tests stopped trusting ARCore, not a number from the docs: a
  // cardboard sheet read correctly down to 0.60m, a hand at 0.45m was completely invisible and
  // smooth depth painted the background straight through it. see REACH-MEASUREMENTS.md
  private static final float NEAR_TRUST_M = 0.60f;
  private static final float FAR_M = 2.00f;

  // how far we read the depth image at all. the corridor stops caring at FAR_M, but the floor strip
  // and the aperture fan both need to see further out than the thing that is about to hit you
  private static final float SCAN_FAR_M = 6.00f;

  // nothing physical can be inside the corridor this close except a hand over the lens. we logged
  // "HAZARD 0.07 m" during brisk walking with nothing there at all, and a safety system that cries
  // seven centimetres once has spent its credibility
  private static final float FLOOR_TRUST_M = 0.25f;

  // speed is a hint that depth is bad, not proof. at 0.30 m/s this threw away 32 frames of
  // valid depth. now it only catches shaking or running; agreement across frames does the real work
  private static final float MAX_TRUST_MPS = 1.5f;

  // the actual quality filter, and the answer to "how do we make the measurements more accurate":
  // we don't make the sensor better, we throw away frames that disagree with their neighbours.
  // garbage doesn't repeat - the 0.07 m and 28 m readings in our logs appeared for a frame and were
  // gone. A real obstacle sits still and every frame agrees about it
  private static final int CONSISTENCY_FRAMES = 5;
  private static final int CONSISTENCY_MIN = 3;
  private static final float CONSISTENCY_SPREAD_M = 0.50f;

  // a repeated depth image for a frame or two is normal, for most of a second is not
  // one logged stall served the same frame for ten seconds while the phone moved 30cm, and a
  // frozen sensor reads as CLEAR
  private static final long STALE_AFTER_MS = 700;

  // one hot pixel out of ~3600 samples must not fire a safety alert. 12 samples at step 2 is
  // roughly a 6cm patch at 1m, which is about the smallest thing worth stopping for
  private static final int MIN_HITS = 12;

  // only used to decide whether incoherent near samples are worth calling blindness over. a
  // scattered handful is the sensor being noisy at the edge of its range; a scattered third of the
  // corridor is the sensor genuinely failing. does NOT gate the coherent case -
  // the near reading that started this was a laptop edge, and a laptop edge is small
  private static final float MIN_NEAR_FRACTION = 0.15f;

  // near samples that agree this tightly are a surface, not noise. a flat thing 40cm away reads
  // with a spread of a few centimetres; scattered garbage spreads over the whole near band
  private static final int COHERENT_SPREAD_MM = 250;

  // a state flip has to survive three frames before we act on it, so a single bad frame can't
  // start or stop a vibration the user is relying on
  private static final int DEBOUNCE_FRAMES = 3;

  public static final class Reading {
    public final State state;
    /** metres to the nearest thing in the corridor, only meaningful when state is HAZARD */
    public final float distanceMeters;
    /** where it sits across the corridor: negative left, positive right, metres from centre */
    public final float lateralMeters;
    public final int hitCount;

    Reading(State state, float distanceMeters, float lateralMeters, int hitCount) {
      this.state = state;
      this.distanceMeters = distanceMeters;
      this.lateralMeters = lateralMeters;
      this.hitCount = hitCount;
    }
  }

  private State stableState = State.CLEAR;
  private State candidateState = State.CLEAR;
  private int candidateFrames = 0;
  private final float[] recentMeters = new float[CONSISTENCY_FRAMES];
  private int recentCount = 0;
  private int recentIndex = 0;
  private int[] hitScratch = new int[4096];
  private int[] nearScratch = new int[4096];
  // filled in the same pass as the corridor test - walking the depth image twice to answer two
  // questions about the same pixels would be silly
  private final float[] floorScratch = new float[4096];
  private final float[] floorZ = new float[4096];
  private int floorCount = 0;
  // kept parallel to hitScratch so we can ask "which side is it on" without a second image pass
  private final float[] hitX = new float[4096];
  private long lastDepthTimestamp = 0;
  private long lastDepthChangeMs = 0;
  private boolean seenDepth = false;
  private long lastDepthAgeMs = 0;
  private long lastDepthTimestampSeen = 0;

  private int nearPercentileMm = 0;
  private boolean usingRaw = false;
  private int selfPoints = 0;

  // the last distance we genuinely measured, held for frames where the debounce still says HAZARD
  private float lastHazardMeters = 0f;
  private float lastHazardLateral = 0f;

  /**
   * How many depth points landed on the wearer's own body this frame - the answer to "is my foot in
   * shot". A screen-space version of this test fired 0 times in 119 frames; gravity does not have
   * to guess which edge of a rotated preview is down.
   */
  public int selfPoints() {
    return selfPoints;
  }
  private float lastBottom = BOTTOM_FALLBACK_M;

  /** where the corridor's floor edge ended up this frame, metres relative to the camera */
  public float corridorBottom() {
    return lastBottom;
  }

  // the free-space channel. it rides along in the same pixel loop because it wants the same points,
  // already converted to gravity-referenced metres - walking the depth image twice would be silly
  private final ApertureScan aperture = new ApertureScan();
  private final OccupancyBeam beam = new OccupancyBeam();

  private Blindness blindness = Blindness.NONE;

  /** why we could not see, valid whenever state is BLIND or STALE */
  public Blindness blindness() {
    return blindness;
  }

  /** the free-space reading from the last evaluate() */
  public ApertureScan.Gap gap() {
    return aperture.lastGap();
  }

  /** the 1D free-distance profile, metres per bearing bin, for the HUD */
  public float[] gapProfile() {
    return aperture.profile();
  }

  /**
   * Which side of the corridor the reported obstacle is on. Averages the sideways position of only
   * those hits that are actually at the reported distance - averaging every hit in the corridor
   * would put a doorway's two jambs dead centre and send the wearer straight into the frame.
   */
  private float lateralOf(float meters, int hits) {
    if (meters <= 0f || hits <= 0) {
      return 0f;
    }
    int counted = Math.min(hits, hitScratch.length);
    float sum = 0f;
    int used = 0;
    for (int i = 0; i < counted; i++) {
      if (Math.abs(hitScratch[i] / 1000f - meters) < 0.25f) {
        sum += hitX[i];
        used++;
      }
    }
    return used == 0 ? 0f : sum / used;
  }

  /** true when the sub-0.60m samples look like one surface rather than scattered noise */
  private boolean nearSamplesAgree(int tooClose) {
    int counted = Math.min(tooClose, nearScratch.length);
    int[] near = Arrays.copyOf(nearScratch, counted);
    Arrays.sort(near);
    nearPercentileMm = near[counted / 20];
    return near[counted * 95 / 100] - nearPercentileMm < COHERENT_SPREAD_MM;
  }

  /** how long since ARCore last handed us a genuinely new depth frame, milliseconds */
  public long depthAgeMs() {
    return lastDepthAgeMs;
  }

  public long depthTimestamp() {
    return lastDepthTimestampSeen;
  }

  /** heights of the floor strip from the last evaluate(), for TerrainWatch to chew on */
  public float[] floorSamples() {
    return floorScratch;
  }

  /**
   * The floor's height relative to the camera, metres and negative, or NaN when we haven't seen
   * enough of it. This is what lets the pointing probe say what it hit rather than only how far -
   * a surface at this height is ground, a hand's width above it is a step.
   */
  public float floorAboveCamera() {
    if (floorCount < 20) {
      return Float.NaN;
    }
    float[] sorted = Arrays.copyOf(floorScratch, floorCount);
    Arrays.sort(sorted);
    // 30th percentile. the list runs most-negative first, so the floor is the low end and clutter
    // sitting on it pushes the high end up - the 60th percentile estimated the floor as being
    // wherever the clutter was, which then raised the corridor above it
    // biasing low only makes the corridor taller, which is the safe direction
    return sorted[floorCount * 3 / 10];
  }

  /** how far ahead each floor sample is, parallel to floorSamples() */
  public float[] floorDistances() {
    return floorZ;
  }

  public int floorSampleCount() {
    return floorCount;
  }

  /**
   * Distance to the nearest floor the camera can actually see, or NaN when it sees none. Large
   * means a blind zone at the wearer's feet.
   *
   * <p>Measured, not derived from pitch and field of view - that version used the sensor's short
   * axis, which is the world's horizontal one in portrait, and reported 0.3m where the real blind
   * zone was several metres.
   */
  /**
   * How far out the floor is still visible, metres, or NaN when none is.
   *
   * <p>Pairs with {@link #nearestFloorMetres}. Free space is only walkable if there is floor under
   * it, and a staircase going down is free space with no floor under it - which is exactly why the
   * aperture fan calls a stairwell a wide open gap. It is answering the question it was asked.
   *
   * <p>Comparing this against how far the fan says you can walk asks the missing question: does the
   * floor run out before the space does. Not a height comparison - three attempts at that failed
   * (see TerrainWatch) because a few hundred pixels at the bottom of a 160x90 image cannot resolve
   * ten centimetres. Whether the floor is there at all is a much coarser question, and coarse is
   * what this data can answer.
   */
  public float furthestFloorMetres() {
    if (floorCount == 0) {
      return Float.NaN;
    }
    float[] sorted = Arrays.copyOf(floorZ, floorCount);
    Arrays.sort(sorted);
    // 90th percentile, not the maximum: one stray far return would push the edge past the drop
    return sorted[(int) (floorCount * 0.9f)];
  }

  public float nearestFloorMetres() {
    if (floorCount == 0) {
      return Float.NaN;
    }
    float nearest = Float.MAX_VALUE;
    for (int i = 0; i < floorCount; i++) {
      nearest = Math.min(nearest, floorZ[i]);
    }
    return nearest;
  }

  /**
   * @param upInCamera world "up" expressed in camera coordinates, from ARCore's pose. Everything
   *     vertical is measured against this instead of against the phone, so tilting the device to
   *     see the floor no longer tilts the corridor into it. Without this the corridor is only
   *     correct when the phone happens to be perfectly level, which it never is on a person's head
   */
  public Reading evaluate(
      Image depthImage, CameraIntrinsics intrinsics, float speedMps, float[] upInCamera) {
    long now = System.currentTimeMillis();
    long timestamp = depthImage.getTimestamp();
    if (timestamp != lastDepthTimestamp || !seenDepth) {
      lastDepthTimestamp = timestamp;
      lastDepthChangeMs = now;
    }
    boolean stale = seenDepth && (now - lastDepthChangeMs) > STALE_AFTER_MS;
    lastDepthAgeMs = seenDepth ? now - lastDepthChangeMs : 0;
    lastDepthTimestampSeen = timestamp;
    seenDepth = true;

    ShortBuffer depth =
        depthImage.getPlanes()[0].getBuffer().order(ByteOrder.nativeOrder()).asShortBuffer();
    usingRaw = false;
    return scan(
        depth,
        depthImage.getWidth(),
        depthImage.getHeight(),
        null,
        0,
        0,
        0,
        intrinsics,
        speedMps,
        upInCamera,
        stale);
  }

  /**
   * The same corridor read from the raw depth stream instead of the smoothed one.
   *
   * <p>The smoothed image once froze for 68 seconds while the raw stream's confidence histogram
   * changed every frame - one sensor dead, the other fine, and we were reading the dead one. Raw
   * is noisier, so the confidence plane filters it and MIN_HITS decides what survives.
   */
  /**
   * Feed the occupancy grid from the raw stream, weighted by ARCore's own confidence.
   *
   * <p>This is separate from evaluate() because the two want different things. The corridor wants
   * the smoothed image, which is dense and gap-free. The grid wants to know how much each pixel is
   * worth, and only the raw stream carries that - smoothed depth fills its holes by interpolation
   * and reports 100% valid whether it knows or is guessing.
   *
   * <p>Confidence is squared. A corridor walk logged 90% of pixels in the lowest confidence band
   * while depth hallucinated a surface at 0.9m on 99% of them; linear weighting still lets that
   * through, squaring makes it worth nothing. A real wall at the same distance, even at a middling
   * confidence of 60, still crosses the threshold on every frame.
   */
  public void observeRaw(
      Image rawDepth,
      Image rawConfidence,
      CameraIntrinsics intrinsics,
      float[] upInCamera,
      float movedMetres) {
    beam.beginFrame(movedMetres);
    ShortBuffer depth =
        rawDepth.getPlanes()[0].getBuffer().order(ByteOrder.nativeOrder()).asShortBuffer();
    Image.Plane plane = rawConfidence.getPlanes()[0];
    ByteBuffer conf = plane.getBuffer().order(ByteOrder.nativeOrder());
    int confRowStride = plane.getRowStride();
    int confPixelStride = plane.getPixelStride();

    int width = rawDepth.getWidth();
    int height = rawDepth.getHeight();
    int[] cameraDims = intrinsics.getImageDimensions();
    float fx = intrinsics.getFocalLength()[0] * width / cameraDims[0];
    float fy = intrinsics.getFocalLength()[1] * height / cameraDims[1];
    float cx = intrinsics.getPrincipalPoint()[0] * width / cameraDims[0];
    float cy = intrinsics.getPrincipalPoint()[1] * height / cameraDims[1];
    float bottom = lastBottom;

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int millimeters = depth.get(y * width + x) & 0xFFFF;
        if (millimeters == 0 || millimeters > SCAN_FAR_M * 1000) {
          continue;
        }
        float z = millimeters / 1000f;
        float pointX = z * (x - cx) / fx;
        float pointY = z * (cy - y) / fy;
        float aboveCamera = pointX * upInCamera[0] + pointY * upInCamera[1] - z * upInCamera[2];
        float rangeSquared = pointX * pointX + pointY * pointY + z * z;
        float ground = (float) Math.sqrt(Math.max(0f, rangeSquared - aboveCamera * aboveCamera));

        if (ground < SELF_RANGE_M || (ground < SELF_GROUND_M && aboveCamera < SELF_HEIGHT_M)) {
          continue;
        }
        if (pointX < -HALF_WIDTH_M || pointX > HALF_WIDTH_M) {
          continue;
        }
        if (aboveCamera > TOP_M || aboveCamera < bottom) {
          continue;
        }
        int c = conf.get(y * confRowStride + x * confPixelStride) & 0xFF;
        float weight = (c / 255f) * (c / 255f);
        beam.observe(ground, weight);
      }
    }
  }

  public float beamSupportAt(float metres) {
    return beam.supportAt(metres);
  }

  public float beamNearest() {
    return beam.nearestOccupied();
  }

  public boolean beamHasEvidence() {
    return beam.hasEvidence();
  }

  public String beamDiagnostics() {
    return beam.diagnostics();
  }

  public Reading evaluateRaw(
      Image rawDepth,
      Image rawConfidence,
      int minConfidence,
      CameraIntrinsics intrinsics,
      float speedMps,
      float[] upInCamera) {
    ShortBuffer depth =
        rawDepth.getPlanes()[0].getBuffer().order(ByteOrder.nativeOrder()).asShortBuffer();
    Image.Plane plane = rawConfidence.getPlanes()[0];
    ByteBuffer confidence = plane.getBuffer().order(ByteOrder.nativeOrder());
    usingRaw = true;
    return scan(
        depth,
        rawDepth.getWidth(),
        rawDepth.getHeight(),
        confidence,
        plane.getRowStride(),
        plane.getPixelStride(),
        minConfidence,
        intrinsics,
        speedMps,
        upInCamera,
        false);
  }

  /** true when the last reading came from the raw stream because the smoothed one had frozen */
  public boolean usingRaw() {
    return usingRaw;
  }

  private Reading scan(
      ShortBuffer depth,
      int width,
      int height,
      ByteBuffer confidence,
      int confRowStride,
      int confPixelStride,
      int minConfidence,
      CameraIntrinsics intrinsics,
      float speedMps,
      float[] upInCamera,
      boolean stale) {

    // the corridor's bottom edge, from the floor we measured last frame. one frame of lag on a
    // surface that is not going anywhere is not worth a second pass over the image
    float floorAbove = floorAboveCamera();
    float bottom;
    if (Float.isNaN(floorAbove)) {
      bottom = BOTTOM_FALLBACK_M;
    } else {
      // always clear the measured floor
      // was min(-1.15, floor + 0.15), which put the edge BELOW the floor whenever the estimate
      // came back shallower than -1.30 - flat floor then read as an obstacle, 63% of frames
      bottom = floorAbove + FLOOR_CLEARANCE_M;
      if (bottom > BOTTOM_CEILING_M) {
        bottom = BOTTOM_CEILING_M;
      }
      if (bottom < BOTTOM_LIMIT_M) {
        bottom = BOTTOM_LIMIT_M;
      }
    }
    lastBottom = bottom;
    // the free-space fan wants the same body slab, for the same reason - a bottle it cannot see is
    // a bottle it will happily route you into
    aperture.setSlabBottom(bottom);

    // intrinsics describe the camera image, which is roughly 25x wider than the 160x90 depth
    // image, so scale them down to depth pixels. this is ARCore's own getter - we are not
    // deriving a calibration, which is the expensive thing we cut
    int[] cameraDims = intrinsics.getImageDimensions();
    float fx = intrinsics.getFocalLength()[0] * width / cameraDims[0];
    float fy = intrinsics.getFocalLength()[1] * height / cameraDims[1];
    float cx = intrinsics.getPrincipalPoint()[0] * width / cameraDims[0];
    float cy = intrinsics.getPrincipalPoint()[1] * height / cameraDims[1];

    int hits = 0;
    int tooClose = 0;
    floorCount = 0;
    aperture.begin();
    selfPoints = 0;
    final int step = 2;
    for (int y = 0; y < height; y += step) {
      for (int x = 0; x < width; x += step) {
        // mask to 16 bits - the buffer is signed shorts and anything past 32.7m comes back
        // negative, which would read as the nearest thing in the room
        int millimeters = depth.get(y * width + x) & 0xFFFF;
        if (confidence != null
            && (confidence.get(y * confRowStride + x * confPixelStride) & 0xFF) < minConfidence) {
          // raw depth without confidence is a random number generator with units
          continue;
        }
        // 0 means "no estimate here", not "touching the lens"
        // cutting at FAR_M here also capped the floor strip and the aperture fan at 2m, and the
        // floor does not enter frame closer than ~4m with the camera level. the corridor's own
        // limit applies further down, where it belongs
        if (millimeters == 0 || millimeters > SCAN_FAR_M * 1000) {
          continue;
        }
        float z = millimeters / 1000f;
        float pointX = z * (x - cx) / fx;
        float pointY = z * (cy - y) / fy;

        // measure height against gravity, not against the phone. camera space has the lens looking
        // down -Z, so the point is (x, y, -z); its height is how far it lies along world-up, and
        // its ground distance is what's left over
        // "height" is taken - it's the depth image's pixel height a few lines up
        float aboveCamera = pointX * upInCamera[0] + pointY * upInCamera[1] - z * upInCamera[2];
        float rangeSquared = pointX * pointX + pointY * pointY + z * z;
        float ground = (float) Math.sqrt(Math.max(0f, rangeSquared - aboveCamera * aboveCamera));

        if (ground < SELF_RANGE_M || (ground < SELF_GROUND_M && aboveCamera < SELF_HEIGHT_M)) {
          // wearer's body, not the world. counted as well as skipped - the person detector
          // needs to know the body is in shot
          // must run BEFORE the floor sampler below, or thighs get averaged into the floor
          // estimate and pull it 20cm shallow, which lifts the corridor above floor clutter
          selfPoints++;
          continue;
        }

        // floor samples for TerrainWatch, kept before the corridor test throws them away. no fixed
        // distance band here: with the camera level at head height the floor doesn't enter frame
        // until about four metres out, so any band we picked in advance would be empty. we take
        // whatever floor is visible and let TerrainWatch split it
        if (aboveCamera < FLOOR_BAND_M
            && Math.abs(pointX) <= FLOOR_SAMPLE_HALF_WIDTH_M
            && ground <= 6f
            && floorCount < floorScratch.length) {
          floorZ[floorCount] = ground;
          floorScratch[floorCount++] = aboveCamera;
        }

        // the free-space channel gets every point in the body slab, out to its own range. it is
        // asking a different question than the corridor - not "what is in my way" but "where is
        // there room" - so it must not inherit the corridor's shoulder-width blinkers
        aperture.add(pointX, aboveCamera, ground);

        // from here down is the corridor's business only, at the corridor's range
        if (z > FAR_M) {
          continue;
        }

        if (pointX < -HALF_WIDTH_M || pointX > HALF_WIDTH_M) {
          continue;
        }
        if (aboveCamera > TOP_M || aboveCamera < bottom) {
          continue;
        }
        // depth along the corridor is now ground distance, so ducking the head doesn't make an
        // obstacle appear closer than it is
        z = ground;
        if (z < FLOOR_TRUST_M) {
          // closer than anything real can be. almost always the wearer's own hand across the lens
          continue;
        }
        if (z < NEAR_TRUST_M) {
          // inside the corridor but below the range our own tests could vouch for. we don't throw
          // it away - we keep it and decide later whether these samples agree with each other
          if (tooClose < nearScratch.length) {
            nearScratch[tooClose] = millimeters;
          }
          tooClose++;
          continue;
        }
        if (hits < hitScratch.length) {
          hitScratch[hits] = millimeters;
          hitX[hits] = pointX;
        }
        hits++;
      }
    }

    aperture.finish();

    State raw;
    float meters = 0f;
    if (speedMps > MAX_TRUST_MPS) {
      // moving too fast for depth-from-motion to keep up. this is the same admission as any other
      // blindness, and it is better than the 28-metre and 7-centimetre readings we logged
      raw = State.BLIND;
      blindness = Blindness.TOO_FAST;
    } else if (stale) {
      // recovery is escalated by the caller: depth mode toggle first, session pause/resume after
      raw = State.STALE;
      blindness = Blindness.FROZEN;
    } else if (tooClose >= MIN_HITS && nearSamplesAgree(tooClose)) {
      // a wall at 45cm is still a wall, and so is the edge of a laptop. the hand that defeated
      // depth at 45cm was small, curved and textureless - a bad target, not proof that everything
      // this close is unreadable. so we report the distance and let the agreement decide whether
      // we believe it, with no minimum size: small things are exactly what a cane misses
      raw = State.HAZARD;
      meters = nearPercentileMm / 1000f;
    } else if (tooClose >= MIN_HITS && tooClose >= MIN_NEAR_FRACTION * (hits + tooClose)) {
      // lots of near samples that don't agree with each other. that isn't an obstacle, it's the
      // sensor coming apart, and it is the one case where saying nothing would be a lie
      raw = State.BLIND;
      blindness = Blindness.INCOHERENT;
    } else if (hits < MIN_HITS) {
      raw = State.CLEAR;
    } else {
      raw = State.HAZARD;
      int counted = Math.min(hits, hitScratch.length);
      int[] sorted = Arrays.copyOf(hitScratch, counted);
      Arrays.sort(sorted);
      // 5th percentile, not the minimum. on a static scene the single nearest pixel wandered by
      // 0.3m and once sat a full metre in front of the real obstacle - alerting on it would mean
      // buzzing at phantoms. the percentile tracked the cardboard to within 2cm
      meters = sorted[counted / 20] / 1000f;
    }

    if (raw == State.HAZARD) {
      recentMeters[recentIndex] = meters;
      recentIndex = (recentIndex + 1) % CONSISTENCY_FRAMES;
      if (recentCount < CONSISTENCY_FRAMES) {
        recentCount++;
      }
      if (recentCount >= CONSISTENCY_MIN) {
        float[] window = Arrays.copyOf(recentMeters, recentCount);
        Arrays.sort(window);
        if (window[recentCount - 1] - window[0] > CONSISTENCY_SPREAD_M) {
          // the last few frames can't agree on where it is, so we don't have a measurement, we
          // have noise with a number attached
          raw = State.BLIND;
          blindness = Blindness.UNSTABLE;
        } else {
          // the median of the window, not the newest reading. this is the whole accuracy story:
          // one bad frame can't move the answer, because four others outvote it
          meters = window[recentCount / 2];
        }
      }
    } else {
      recentCount = 0;
      recentIndex = 0;
    }

    if (raw != State.BLIND && raw != State.STALE) {
      blindness = Blindness.NONE;
    }

    if (raw == candidateState) {
      candidateFrames++;
    } else {
      candidateState = raw;
      candidateFrames = 1;
    }
    if (candidateFrames >= DEBOUNCE_FRAMES) {
      stableState = candidateState;
    }

    float lateral = lateralOf(meters, hits);
    if (meters > 0f) {
      lastHazardMeters = meters;
      lastHazardLateral = lateral;
    } else if (stableState == State.HAZARD && lastHazardMeters > 0f) {
      // the debounce is still holding HAZARD from earlier frames, but this frame measured nothing.
      // zero is an absence, not a measurement, and it reached the wearer as "obstacle, 0.0 metres"
      // on 44 of 319 hazard frames. report the last distance we actually had instead
      meters = lastHazardMeters;
      lateral = lastHazardLateral;
    }
    return new Reading(stableState, meters, lateral, hits);
  }
}
