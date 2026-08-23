/*
 * Reach - haptic output.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Vibration patterns, kept few.
 *
 * <p>A user can learn three tactile signals in a minute. They cannot learn fifteen at all, and a
 * system nobody can read is worse than one that says less. So Guardian gets a rhythm, not a
 * vocabulary: a double-buzz whose repeat rate climbs as the obstacle gets closer. Distance lives
 * in the tempo, which people read without being taught.
 */
public class HapticEngine {

  // a double tap is hard to confuse with a notification and hard to confuse with the single long
  // buzz below. gap is short enough that the two taps read as one event
  private static final long[] GUARDIAN_DOUBLE_BUZZ = {0, 55, 45, 55};

  // slowest and fastest repeat. 1100ms at the far edge of the corridor is a background heartbeat;
  // 220ms at 0.6m is urgent without becoming a continuous buzz, which stops carrying information
  private static final long SLOWEST_REPEAT_MS = 1100;
  private static final long FASTEST_REPEAT_MS = 220;
  private static final float FAR_M = 2.00f;
  private static final float NEAR_M = 0.60f;

  // one long buzz for "there is something in the corridor and I cannot measure it". this is a
  // fourth pattern and the plan said three - we added it because our own tests showed ARCore
  // reporting a confident wrong distance through a hand at 45cm, and a system that stays silent
  // when it is blind is indistinguishable from one that says "clear". flip this to false to get
  // back to the three-pattern design
  private static final boolean BLIND_SIGNAL_ENABLED = true;
  private static final long[] BLIND_LONG_BUZZ = {0, 400};
  private static final long BLIND_REPEAT_MS = 1500;

  // "blind, but you can fix it" - the same long buzz so it still reads as blindness, with two
  // taps after it. not a fifth unrelated pattern: the user has one thing to learn,
  // that the tail means there's an action available
  private static final long[] SWAY_PROMPT = {0, 400, 120, 70, 90, 70};
  private static final long SWAY_REPEAT_MS = 1800;

  // long-short-long, the least like the other two: Guardian is two equal taps, the
  // portal answer is one, and this is a shape you feel the ends of. the ground giving way is the
  // one signal a user must never mistake for anything else
  private static final long[] TERRAIN_PATTERN = {0, 220, 90, 70, 90, 220};
  private static final long DROP_REPEAT_MS = 500;
  private static final long STEP_UP_REPEAT_MS = 1000;

  // one tap, not two. Guardian's double-buzz is the world interrupting you; a single tap is the
  // answer to something you asked. the pair stay tellable apart without anyone being taught
  private static final long[] PROBE_TAP = {0, 40};
  private static final long PROBE_SLOWEST_MS = 650;
  private static final long PROBE_FASTEST_MS = 110;
  private static final float PROBE_FAR_M = 4.0f;

  private final Vibrator vibrator;
  private long nextFireMs = 0;

  public HapticEngine(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      VibratorManager manager =
          (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
      vibrator = manager.getDefaultVibrator();
    } else {
      vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }
  }

  /** call every frame - this decides on its own whether it's time to fire */
  public void update(GuardianCorridor.Reading reading, boolean askForMotion) {
    if (vibrator == null || !vibrator.hasVibrator()) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now < nextFireMs) {
      return;
    }

    switch (reading.state) {
      case HAZARD:
        fire(GUARDIAN_DOUBLE_BUZZ);
        nextFireMs = now + repeatIntervalMs(reading.distanceMeters);
        break;
      case BLIND:
      case STALE:
        // both mean "I cannot see", and a user has no use for knowing which flavour of blind it
        // is - the action is the same either way: stop, and use the cane
        if (!BLIND_SIGNAL_ENABLED) {
          break;
        }
        if (askForMotion) {
          fire(SWAY_PROMPT);
          nextFireMs = now + SWAY_REPEAT_MS;
        } else {
          fire(BLIND_LONG_BUZZ);
          nextFireMs = now + BLIND_REPEAT_MS;
        }
        break;
      case CLEAR:
      default:
        // nothing. silence has to mean clear, so it can never also mean "sensor gave up"
        break;
    }
  }

  /**
   * a person the depth sensor can't see. same double-buzz as any other hazard, because to the user
   * it means the same thing - something is in your way - and a fourth rhythm buys nothing. we have
   * no distance for them, so the rate is fixed rather than invented
   */
  public void updatePerson() {
    if (vibrator == null || !vibrator.hasVibrator()) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now < nextFireMs) {
      return;
    }
    fire(GUARDIAN_DOUBLE_BUZZ);
    nextFireMs = now + 600;
  }

  /**
   * @param drop true for ground falling away, false for a step up. same pattern, faster repeat for
   *     the drop - a kerb you walk up is a stumble, a kerb you walk off is a fall
   */
  public void updateTerrain(boolean drop) {
    if (vibrator == null || !vibrator.hasVibrator()) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now < nextFireMs) {
      return;
    }
    fire(TERRAIN_PATTERN);
    nextFireMs = now + (drop ? DROP_REPEAT_MS : STEP_UP_REPEAT_MS);
  }

  /**
   * The free-space channel. One motor cannot encode a direction, so bearing goes to speech, which
   * has words for left and right, and fit stays on the skin as the double buzz the corridor
   * already uses. Urgent means no way through, slow means turn your shoulders, silence means walk.
   */
  public void updateGap(boolean blocked, boolean squeeze) {
    if (vibrator == null || !vibrator.hasVibrator()) {
      return;
    }
    if (!blocked && !squeeze) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now < nextFireMs) {
      return;
    }
    fire(GUARDIAN_DOUBLE_BUZZ);
    nextFireMs = now + (blocked ? FASTEST_REPEAT_MS : 900);
  }

  /** the pointing probe answering a question the wearer asked */
  public void updateProbe(float meters) {
    if (vibrator == null || !vibrator.hasVibrator()) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now < nextFireMs) {
      return;
    }
    float t = Math.max(0f, Math.min(1f, (meters - NEAR_M) / (PROBE_FAR_M - NEAR_M)));
    fire(PROBE_TAP);
    nextFireMs = now + (long) (PROBE_FASTEST_MS + t * (PROBE_SLOWEST_MS - PROBE_FASTEST_MS));
  }

  /**
   * Safe to call every frame, which the quiet modes do. cancel() is a call into a system service,
   * and doing that thirty times a second to stop nothing is work the phone does not need while it
   * is also running two neural networks.
   */
  public void stop() {
    if (nextFireMs == 0) {
      return;
    }
    if (vibrator != null) {
      vibrator.cancel();
    }
    nextFireMs = 0;
  }

  private long repeatIntervalMs(float meters) {
    float t = (meters - NEAR_M) / (FAR_M - NEAR_M);
    t = Math.max(0f, Math.min(1f, t));
    return (long) (FASTEST_REPEAT_MS + t * (SLOWEST_REPEAT_MS - FASTEST_REPEAT_MS));
  }

  @SuppressWarnings("deprecation")
  private void fire(long[] pattern) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
    } else {
      // minSdk is 24 and VibrationEffect landed in 26, so the old call has to stay
      vibrator.vibrate(pattern, -1);
    }
  }
}
