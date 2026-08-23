/*
 * Reach - which sense is allowed to speak.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

/**
 * Four senses, one wearer, one skin. Decides who gets the output, not what is out there.
 *
 * <p>Running every channel at once was unusable: a dresser two metres away is an obstacle, a gap
 * either side of it, and a change in the floor, so all three fired at once. Every sentence true,
 * the output noise.
 *
 * <p>Who gets the output is decided by how much each sense trusts itself, not by a fixed order. One rule outranks
 * it: an obstacle inside stopping distance is not a routing problem, in any mode.
 */
public class ModeArbiter {

  /** what the wearer selected */
  public enum Mode {
    /** ask-and-answer only: the L gesture and its description. the world stays quiet */
    SCENE,
    /** the original: what is in your way, and how far */
    GUARDIAN,
    /** free space: where the gap is and whether you fit */
    APERTURE,
    /** let the device pick, from how much each sense can currently be trusted */
    AUTO
  }

  /** who is actually driving the output this frame */
  public enum Channel {
    QUIET,
    TERRAIN,
    GUARDIAN,
    APERTURE
  }

  // an obstacle nearer than this owns the skin no matter what mode is selected. below one metre a
  // wearer at walking pace has under a second, which is not enough time to be given a choice
  private static final float IMMINENT_M = 1.00f;

  // the fan is only worth listening to inside a band
  // beyond 2.5m it narrates furniture across the room; under 1.2m there is no routing decision
  // left to make and the honest output is a distance and a stop, not a bearing
  private static final float APERTURE_USEFUL_M = 2.50f;
  private static final float APERTURE_MIN_BARRIER_M = 1.20f;

  // and only when it can actually see. below this the fan is guessing from a handful of bins
  private static final float APERTURE_MIN_COVERAGE = 0.60f;

  // the floor strip is the thinnest evidence in the system - a few hundred pixels at the bottom of
  // a 160x90 image, at the far edge of the depth range. it does not get the channel on one frame
  private static final int TERRAIN_MIN_SAMPLES = 200;
  private static final int TERRAIN_STABLE_FRAMES = 6;

  // how far the bearing has to move before the same advice counts as new advice. repeating "gap
  // eighty centimetres, fifteen left" every five seconds while the wearer walks towards it is the
  // fastest way to make someone switch the device off
  private static final float BEARING_BUCKET_DEG = 15f;

  private Mode mode = Mode.SCENE;
  private Channel channel = Channel.QUIET;

  private TerrainWatch.State terrainCandidate = TerrainWatch.State.UNKNOWN;
  private int terrainFrames = 0;

  private ApertureScan.Fit lastSpokenFit = null;
  private int lastSpokenBucket = Integer.MIN_VALUE;

  public Mode mode() {
    return mode;
  }

  public Channel channel() {
    return channel;
  }

  /** step to the next mode, for the on-screen button */
  public Mode cycle() {
    Mode[] all = Mode.values();
    mode = all[(mode.ordinal() + 1) % all.length];
    // a mode change invalidates what was last said, so the new mode's first sentence is not
    // swallowed as a repeat
    lastSpokenFit = null;
    lastSpokenBucket = Integer.MIN_VALUE;
    return mode;
  }

  public void setMode(Mode m) {
    mode = m;
    lastSpokenFit = null;
    lastSpokenBucket = Integer.MIN_VALUE;
  }

  /** spoken aloud when the mode changes, so the wearer never has to look */
  public static String announce(Mode m) {
    switch (m) {
      case SCENE:
        return "Scene mode. Make an L to ask";
      case GUARDIAN:
        return "Guardian mode. Obstacles";
      case APERTURE:
        return "Aperture mode. Free space";
      default:
        return "Automatic mode";
    }
  }

  public static String shortName(Mode m) {
    switch (m) {
      case SCENE:
        return "SCENE";
      case GUARDIAN:
        return "GUARDIAN";
      case APERTURE:
        return "APERTURE";
      default:
        return "AUTO";
    }
  }

  /**
   * Decide who drives this frame.
   *
   * @param terrainSamples how many floor points the depth image actually gave us
   */
  public Channel decide(
      GuardianCorridor.Reading reading,
      ApertureScan.Gap gap,
      TerrainWatch.State terrain,
      int terrainSamples) {

    // terrain confidence is tracked in every mode, so switching into AUTO does not start from zero
    if (terrain == terrainCandidate) {
      terrainFrames++;
    } else {
      terrainCandidate = terrain;
      terrainFrames = 1;
    }
    boolean terrainTrusted =
        TerrainWatch.ENABLED
            && terrainSamples >= TERRAIN_MIN_SAMPLES
            && terrainFrames >= TERRAIN_STABLE_FRAMES
            && (terrain == TerrainWatch.State.DROP || terrain == TerrainWatch.State.STEP_UP);

    boolean imminent =
        reading != null
            && reading.state == GuardianCorridor.State.HAZARD
            && reading.distanceMeters < IMMINENT_M;

    if (imminent) {
      // no mode, no sensor and no preference outranks this
      channel = Channel.GUARDIAN;
      return channel;
    }

    // a drop is the one thing a cane finds and a forward sensor does not, so when the floor strip
    // is genuinely confident it beats routing advice in every mode except the quiet one
    if (terrainTrusted && mode != Mode.SCENE) {
      channel = Channel.TERRAIN;
      return channel;
    }

    switch (mode) {
      case SCENE:
        channel = Channel.QUIET;
        break;
      case GUARDIAN:
        channel = Channel.GUARDIAN;
        break;
      case APERTURE:
        channel = Channel.APERTURE;
        break;
      default:
        channel = apertureTrusted(gap) ? Channel.APERTURE : Channel.GUARDIAN;
        break;
    }
    return channel;
  }

  /**
   * Whether the free-space channel has earned the output right now. Coverage is the fan's own
   * admission of how much of itself it could read; the barrier test is what stops it narrating
   * furniture on the far side of the room.
   */
  public boolean apertureTrusted(ApertureScan.Gap gap) {
    return gap != null
        && gap.fit != ApertureScan.Fit.UNKNOWN
        && gap.coverage >= APERTURE_MIN_COVERAGE
        && gap.barrierM <= APERTURE_USEFUL_M
        && gap.barrierM >= APERTURE_MIN_BARRIER_M;
  }

  /**
   * True only when the free-space advice has actually changed. Without this the aperture repeats
   * itself at the speech manager's cooldown forever, because a wearer walking towards a doorway is
   * looking at the same doorway for several seconds and every frame is news.
   */
  public boolean gapAdviceIsNew(ApertureScan.Gap gap) {
    int bucket = Math.round(gap.bearingDeg / BEARING_BUCKET_DEG);
    if (gap.fit == lastSpokenFit && bucket == lastSpokenBucket) {
      return false;
    }
    lastSpokenFit = gap.fit;
    lastSpokenBucket = bucket;
    return true;
  }

  /** what the HUD prints under the mode name, so the arbitration is visible during judging */
  public String explain(ApertureScan.Gap gap, int terrainSamples) {
    switch (channel) {
      case TERRAIN:
        return "floor strip trusted · " + terrainSamples + " samples, " + terrainFrames
            + " frames";
      case APERTURE:
        return gap == null
            ? "free space"
            : String.format(
                java.util.Locale.US,
                "free space · %.0f%% of fan read, barrier %.1f m",
                gap.coverage * 100f, gap.barrierM);
      case GUARDIAN:
        if (gap != null && mode == Mode.AUTO) {
          return gap.coverage < APERTURE_MIN_COVERAGE
              ? String.format(
                  java.util.Locale.US, "fan only %.0f%% read — falling back to depth",
                  gap.coverage * 100f)
              : gap.barrierM < APERTURE_MIN_BARRIER_M
                  ? String.format(
                      java.util.Locale.US, "barrier at %.1f m — too close to route, use distance",
                      gap.barrierM)
                  : String.format(
                      java.util.Locale.US, "nothing within %.1f m to route around",
                      APERTURE_USEFUL_M);
        }
        return "obstacles in the corridor";
      default:
        return "say look, or make an L";
    }
  }
}
