/*
 * The Eye - remembering what was seen, and where it will be.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps objects alive between the frames the detector is not looking at them.
 *
 * <p>Two clocks disagree. The corridor measures depth 30 times a second; the detector names things
 * 5 times a second and only sees them when they are big enough and lit enough. So the frame where
 * the corridor decides to speak is usually a frame with no box in it, and the wearer hears
 * "obstacle" about a chair the screen had already labelled.
 *
 * <p>Worse, a single bad frame gets spoken with full confidence. One logged run labelled a backpack
 * a toilet exactly once, and once is enough when it reaches someone's ear.
 *
 * <p>So a sighting is not an answer, it is evidence. Each object becomes a track that remembers
 * where it was, how far away, and when. A track needs two sightings before it may be named, which
 * throws away the one-frame mistakes. And because the wearer is walking, a track that was 2.4m away
 * a second ago is not 2.4m away now: the remembered distance is carried forward at the measured
 * walking speed, so the corridor's reading still matches it as you close in.
 */
public class ObjectMemory {

  /** a track goes stale this long after its last sighting */
  private static final long FORGET_MS = 2500;

  /** two sightings before we say the name out loud */
  private static final int MIN_SIGHTINGS = 2;

  /** how close a new sighting has to be, in image widths, to count as the same thing */
  private static final float SAME_OBJECT_U = 0.22f;

  /** how far the corridor's reading may sit from a track's predicted distance and still match */
  private static final float MATCH_TOLERANCE_M = 0.60f;

  /** prediction is only trusted this far ahead; past it the track is just a memory */
  private static final long PREDICT_LIMIT_MS = 1500;

  /**
   * How much of the frame an object fills before we call it within arm's reach whatever depth says.
   *
   * <p>Depth-from-motion has no useful parallax at contact range. Pressed against a suitcase the
   * centre of the depth image reported 1.2 to 2.5 metres on 90% of frames and nothing at all below
   * 0.7 - it paints the room straight through the thing you are touching, the same failure a hand
   * at 45cm produced. Apparent size does not have that problem: something filling half the picture
   * is close, and that is true of every lens ever made.
   */
  private static final float CONTACT_FRAME_SHARE = 0.42f;

  private static final class Track {
    String label;
    float centreU;
    float centreV;
    float metres;
    float lateral;
    float frameShare;
    long lastSeenMs;
    int sightings;
  }

  private final List<Track> tracks = new ArrayList<>();
  private String lastMatched = "none";

  /**
   * Record one sighting.
   *
   * @param metres distance to the object, or NaN when depth could not measure it
   */
  public void observe(
      String label,
      float centreU,
      float centreV,
      float metres,
      float lateral,
      float frameShare,
      long nowMs) {
    if (label == null) {
      return;
    }
    Track best = null;
    float bestGap = SAME_OBJECT_U;
    for (Track track : tracks) {
      if (!track.label.equals(label)) {
        continue;
      }
      float gap = Math.abs(track.centreU - centreU);
      if (gap < bestGap) {
        bestGap = gap;
        best = track;
      }
    }
    if (best == null) {
      best = new Track();
      best.label = label;
      tracks.add(best);
    }
    best.centreU = centreU;
    best.centreV = centreV;
    best.frameShare = frameShare;
    if (!Float.isNaN(metres)) {
      best.metres = metres;
      best.lateral = lateral;
    }
    best.lastSeenMs = nowMs;
    best.sightings++;
  }

  /** drop tracks nobody has seen for a while. call once per frame */
  public void expire(long nowMs) {
    for (int i = tracks.size() - 1; i >= 0; i--) {
      if (nowMs - tracks.get(i).lastSeenMs > FORGET_MS) {
        tracks.remove(i);
      }
    }
  }

  /**
   * What the corridor is most likely looking at, or null.
   *
   * @param metres what the corridor measured this frame
   * @param speedMps how fast the wearer is walking, which is how fast a remembered distance shrinks
   */
  public String nameAt(float metres, float lateral, long nowMs, float speedMps) {
    String best = null;
    float bestError = MATCH_TOLERANCE_M;
    for (Track track : tracks) {
      if (track.sightings < MIN_SIGHTINGS || track.metres <= 0f) {
        continue;
      }
      long age = nowMs - track.lastSeenMs;
      if (age > PREDICT_LIMIT_MS) {
        continue;
      }
      // walking towards a thing closes the distance at the speed you are walking
      float predicted = track.metres - speedMps * (age / 1000f);
      float error = Math.abs(predicted - metres);
      // a track on the other side of the corridor is not what we just measured
      if (Math.abs(track.lateral - lateral) > 0.5f) {
        continue;
      }
      if (error < bestError) {
        bestError = error;
        best = track.label;
      }
    }
    lastMatched =
        best == null
            ? String.format(Locale.US, "none/%d", tracks.size())
            : String.format(Locale.US, "%s@%.2f", best, bestError);
    return best;
  }

  /**
   * The label of anything currently filling enough of the frame to be within reach, or null.
   *
   * <p>This outranks the depth reading rather than averaging with it. Two sensors disagreeing about
   * whether a thing is at arm's length or two metres away is not a case for splitting the
   * difference; one of them is wrong and we know which one, because we measured it.
   */
  public String contact(long nowMs) {
    for (Track track : tracks) {
      if (track.sightings >= MIN_SIGHTINGS
          && track.frameShare >= CONTACT_FRAME_SHARE
          && nowMs - track.lastSeenMs < PREDICT_LIMIT_MS) {
        return track.label;
      }
    }
    return null;
  }

  public String diagnostics() {
    return lastMatched;
  }

  public int trackCount() {
    return tracks.size();
  }
}
