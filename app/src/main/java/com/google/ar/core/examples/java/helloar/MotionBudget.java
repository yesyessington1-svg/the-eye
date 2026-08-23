/*
 * Reach - motion budget.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

/**
 * Depth here comes from motion, so the wearer is part of the sensor.
 *
 * <p>Measured: standing still gives ~61% raw coverage and 1.6% of pixels above half confidence;
 * walking at 5-30 cm/s gives 95% and 50%; above 30 cm/s smooth depth stops entirely; from cold it
 * gave nothing for eleven seconds until the phone had moved 12cm.
 *
 * <p>ToF and stereo systems see fine standing still. Ours does not, so rather than go quietly
 * blind it asks the wearer to sway - the same bargain a cane makes by being swept. The cue fires
 * only when motion would help; depth held steady for 42 seconds once warm.
 */
public class MotionBudget {

  // below this the phone is "still" for our purposes. hand tremor and breathing put a held phone
  // at roughly 1-5 cm/s, and 12 cm of travel is what it took to boot depth from cold
  private static final float STILL_MPS = 0.05f;

  // don't nag instantly - a second and a half of being blind and still is a real problem, half a
  // second is someone turning their head
  private static final long PATIENCE_MS = 1500;

  private final float[] lastTranslation = new float[3];
  private boolean havePrevious = false;
  private long lastSampleMs = 0;
  private float speedMps = 0f;
  private long blindAndStillSinceMs = 0;

  /**
   * @param translation camera position, from Pose.getTranslation
   * @param losingPicture true when depth is stale, blind, or not arriving at all
   */
  public void update(float[] translation, boolean losingPicture) {
    long now = System.currentTimeMillis();
    if (havePrevious) {
      float dx = translation[0] - lastTranslation[0];
      float dy = translation[1] - lastTranslation[1];
      float dz = translation[2] - lastTranslation[2];
      float elapsed = Math.max(1, now - lastSampleMs) / 1000f;
      float instant = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / elapsed;
      // heavy smoothing - we care whether someone is standing still, not whether they
      // twitched, and a jumpy estimate would make the cue flicker on and off
      speedMps = speedMps + 0.15f * (instant - speedMps);
    }
    System.arraycopy(translation, 0, lastTranslation, 0, 3);
    lastSampleMs = now;
    havePrevious = true;

    if (losingPicture && speedMps < STILL_MPS) {
      if (blindAndStillSinceMs == 0) {
        blindAndStillSinceMs = now;
      }
    } else {
      blindAndStillSinceMs = 0;
    }
  }

  /**
   * cold start, before ARCore tracks anything. there is no pose yet so we cannot measure whether
   * the user is still - but this is precisely the case where ARCore gave us nothing for eleven
   * seconds until the phone had travelled 12cm, so asking for motion is right without evidence
   */
  public void updateWithoutPose() {
    long now = System.currentTimeMillis();
    havePrevious = false;
    speedMps = 0f;
    if (blindAndStillSinceMs == 0) {
      blindAndStillSinceMs = now;
    }
  }

  /** true when moving would genuinely recover the picture, not merely when we can't see */
  public boolean shouldAskForMotion() {
    return blindAndStillSinceMs != 0
        && System.currentTimeMillis() - blindAndStillSinceMs > PATIENCE_MS;
  }

  public float speedMps() {
    return speedMps;
  }

  public void reset() {
    havePrevious = false;
    speedMps = 0f;
    blindAndStillSinceMs = 0;
  }
}
