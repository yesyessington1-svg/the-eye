/*
 * Reach - camera-side detection: the shutter gesture, and people depth can't see.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.Image;
import android.util.Log;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.components.containers.Landmark;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything Reach reads out of the colour image: the shutter gesture, and objects.
 *
 * <p>The L gesture is only a trigger, not a viewfinder - someone who cannot see the frame cannot
 * aim it, and a forehead-mounted phone has no reachable button.
 *
 * <p>Objects matter because depth-from-motion is blind to exactly what moves: a hand at 45cm was
 * invisible and a person froze the stream outright. Colour shares none of those failure modes.
 *
 * <p>Both models share one worker and one frame. CPU delegate only - the GPU delegate pins to the
 * thread owning its EGL context, which is where ARCore's renderer lives.
 */
public class VisionDetector {

  private static final String TAG = "ReachVision";

  private static final int WRIST = 0;
  private static final int THUMB_MCP = 2;
  private static final int THUMB_TIP = 4;
  private static final int INDEX_MCP = 5;
  private static final int INDEX_TIP = 8;
  private static final int MIDDLE_MCP = 9;
  private static final int MIDDLE_TIP = 12;

  // the wearer's own hand against a stranger's, by apparent size. Set at 0.22 from an early session
  // where the hand was held close to the lens; in normal use at arm's length it measures 0.13 to
  // 0.21, so that threshold was throwing away nearly every real gesture before anything else got
  // to look at it. A stranger's hand across a room is still far below this
  private static final float MIN_HAND_SPAN = 0.12f;

  // the object detector calls a bare hand a person, which is true and useless to us: Guardian warns
  // about people the wearer might walk into, not about the wearer's own arm
  // any detection sitting on top of the wearer's own hand is the wearer's own arm. This was 0.5 -
  // half the person box had to be hand before we dropped it - and the detector's box wraps hand,
  // forearm and sleeve, so a real arm never reached half. The result was the system announcing
  // "person ahead" at the wearer's own finger, and the pointing picking that box because it was
  // of course exactly in the direction they were pointing.
  // a token overlap, not a majority. see mostlyTheWearersHand() for the 17-of-17 measurement that
  // took this down from 0.15
  private static final float HAND_OVERLAP_REJECT = 0.02f;

  // a raised arm's box wraps hand, forearm and sleeve and measured 1.4x to 19.7x the hand's area
  // across one session - so 6x was rejecting only the tightest crops and letting the rest through
  // as "person". The cost of raising it: someone standing directly behind the wearer's raised hand
  // may be missed for the second or two the hand is up. Their hand being up is a deliberate act,
  // and a channel that cries wolf at your own finger is one nobody keeps listening to
  private static final float MAX_ARM_TO_HAND = 14.0f;

  // pointing, as distinct from the L: index out, middle curled. measured in metres from the world
  // landmarks - in the image a finger aimed at the camera is a few pixels long however far it
  // reaches, so the image version rejected exactly the case the 3D ray was built for
  // a ratio, so it holds for any hand size
  private static final float POINT_RATIO = 1.35f;
  private static final float INDEX_MIN_M = 0.04f;

  // an L is thumb AND index out. tip-to-tip distance alone could not tell it from pointing - the
  // thumb sits off to the side either way, so left, right and down all read as an L
  // thumb position against the palm is what separates them
  private static final float L_SPREAD_M = 0.07f;

  // thumb tip to middle knuckle: tucked against the fist when pointing, out when making an L
  // thumb LENGTH doesn't change with pose (5.7-6.1cm every frame) and tip-to-tip spread overlapped
  // almost completely (6.9-9.7 pointing vs 7.1-9.7 for an L)
  private static final float THUMB_TUCKED_M = 0.075f;

  // the relaxed pair, used only once the gesture is already recognised. set below the measured
  // floor of the rejected frames (0.068 median) so a hold that is genuinely still up survives, and
  // still far above a closed fist, which is what this test exists to exclude
  private static final float L_SPREAD_HOLD_M = 0.055f;
  private static final float THUMB_TUCKED_HOLD_M = 0.060f;

  // detection runs a few times a second, so a gap this long means the thing is actually gone
  // rather than that we simply haven't looked yet
  private static final long FRESH_FOR_MS = 500;

  // how long a hand keeps counting as in-frame after the landmarker last found it. covers three
  // consecutive misses at the 200ms detection interval, which is what the measured drop rate needs
  private static final long HAND_BOX_STICKY_MS = 700;

  // the gesture gets a longer memory than everything else, because it is the one channel where a
  // dropout costs the wearer a retry rather than a stale reading
  private static final long GESTURE_FRESH_MS = 800;

  // COCO label 0. we filter here in Java rather than with setCategoryAllowlist, which crashes under
  // the GPU delegate (mediapipe#5614) - we're on CPU, but a landmine you can step around is worth
  // stepping around
  private static final String PERSON = "person";
  private static final float PERSON_SCORE = 0.45f;

  // everything the detector finds, not just people. the person channel needed one class; pointing
  // at things needs all of them, because the useful answer is "a chair" and not "an obstacle"
  // back up from 0.22, which was a mistake we could hear: at that threshold the detector called a
  // water bottle a person. A confidently wrong name is worse than no name - the wearer has no way
  // to check it, and one absurd answer costs more trust than ten silences
  private static final float OBJECT_SCORE = 0.40f;

  // COCO has eighty classes and most of them are not things a walker meets. Logged runs in a
  // furnished flat produced frisbee, toothbrush, tie and surfboard - all misfires on household
  // clutter, all of them spoken aloud with full confidence. A wrong noun is worse than no noun:
  // it costs the wearer's trust in every other noun. So we answer only for things that can be
  // walked into, tripped over, or walked round
  private static final java.util.Set<String> MOBILITY_CLASSES =
      new java.util.HashSet<>(
          java.util.Arrays.asList(
              "person", "bicycle", "car", "motorcycle", "bus", "truck", "bench", "chair",
              "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
              "refrigerator", "oven", "sink", "microwave", "suitcase", "backpack", "handbag",
              "bottle", "vase", "cup", "bowl", "book", "dog", "cat", "traffic light",
              "fire hydrant", "stop sign", "parking meter"));
  private static final int MAX_OBJECTS = 12;

  private final HandLandmarker hands;
  private final ObjectDetector objects;
  private final YuvToBitmap converter = new YuvToBitmap();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy = new AtomicBoolean(false);

  private volatile int rotationApplied = 90;
  private java.util.List<Landmark> lastWorld = null;
  private final RectF handBox = new RectF();
  // when the hand box was last seen, not whether it was seen on THIS frame
  // as a plain boolean the arm filter rejected 0 of 22 person boxes: the person channel only runs
  // when no hand is detected, so every box it sees comes from a frame where the box was just
  // cleared. the landmarker drops about a third of frames on a hand that is plainly there
  private long handBoxMs = 0;
  // bring-up diagnostics: why the gesture or the person filter did or didn't fire
  private volatile String lastHandReason = "no-hand";
  private volatile int lastPersonRaw = 0;
  private volatile int lastPersonRejected = 0;
  private volatile String lastPersonRatio = "-";
  private volatile boolean ownFeet = false;

  // a box resting on the bottom edge of the view that never reaches the middle of it. To see the
  // floor two metres out the phone has to tilt down about forty degrees, and at that angle the
  // wearer's own feet are in shot - we have the screenshot of a tidy "person" box around a sock.
  // someone you are actually walking towards spans much more of the frame vertically long before
  // they are close enough to be worth an interruption
  private static final float FEET_BOTTOM_V = 0.94f;
  private static final float FEET_TOP_V = 0.55f;

  /** true when the only person in view is the wearer's own feet */
  public boolean personLooksLikeOwnFeet() {
    return ownFeet;
  }
  private volatile long gestureSeenMs = 0;
  private volatile long pointingSeenMs = 0;
  private volatile long handSeenMs = 0;
  // unit direction of the finger in ARCore camera space, and where the fingertip sits in the
  // original unrotated image as normalised coordinates
  private volatile float[] pointingDirection = new float[3];
  private volatile float[] pointingDirectionAlt = new float[3];

  // every detection, in the ORIGINAL un-rotated image's normalised coordinates: four floats a box
  private volatile float[] objectBoxes = new float[MAX_OBJECTS * 4];
  private volatile String[] objectLabels = new String[MAX_OBJECTS];
  private volatile int objectCount = 0;
  private volatile String lastObjectNames = "-";
  private volatile float[] fingertipImage = new float[2];
  // the finger's direction drawn on the picture, un-rotated. this is all the hitbox test needs
  private volatile float[] pointingImageDir = new float[2];
  private volatile long personSeenMs = 0;
  private volatile float personFrameShare = 0f;

  // person boxes in the ORIGINAL unrotated image's normalised coordinates. we detect on a rotated
  // bitmap, so these are un-rotated on the way out - the alternative is making every consumer know
  // which way the frame was turned, which is how coordinate bugs are born
  private static final int MAX_BOXES = 8;
  private final float[] boxBuffer = new float[MAX_BOXES * 4];
  private volatile float[] personBoxes = new float[MAX_BOXES * 4];
  private volatile int personBoxCount = 0;

  public VisionDetector(Context context) {
    hands =
        HandLandmarker.createFromOptions(
            context,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build());
    objects =
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        // Lite0 called a water bottle a person and emitted a person box on 17 of
                        // 17 frames where a hand was up. Lite2 is the next size up, 26% to 36%
                        // COCO mAP, +20MB, ~60ms on a worker at 1Hz
                        .setModelAssetPath("efficientdet_lite2.tflite")
                        .build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(PERSON_SCORE)
                .setMaxResults(8)
                .build());
  }

  /**
   * Hand over the current camera frame; returns immediately. Frames arriving while the worker is
   * busy are dropped rather than queued - a backlog would make the shutter fire late and the person
   * warning describe a room the user has already walked out of.
   */
  /**
   * @param uprightDegrees how far to turn the frame so the room comes out the right way up. A
   *     hardcoded 90 assumed one grip; held any other way the detector saw a sideways room, and a
   *     detector trained on upright photographs does not find a sideways chair.
   */
  public void offer(Image cameraImage, int uprightDegrees) {
    if (!busy.compareAndSet(false, true)) {
      return;
    }
    // full resolution, straightened. halving it costs recall - a chair across the room is a
    // handful of pixels once three quarters of them are thrown away
    final YuvToBitmap.Frame frame;
    try {
      // only the byte copy happens here. the JPEG round-trip is the worker's problem, because
      // this method runs on the render thread and ARCore needs that thread back
      frame = converter.grab(cameraImage);
    } catch (RuntimeException e) {
      Log.w(TAG, "camera image grab failed", e);
      busy.set(false);
      return;
    }
    rotationApplied = uprightDegrees;
    worker.execute(
        () -> {
          Bitmap bitmap = null;
          try {
            bitmap = converter.decode(frame, 1, uprightDegrees);
            if (bitmap == null) {
              return;
            }
            MPImage input = new BitmapImageBuilder(bitmap).build();
            long now = System.currentTimeMillis();
            // hands first: the person filter needs to know where the wearer's own arm is
            if (findHand(hands.detect(input), bitmap.getWidth(), bitmap.getHeight())) {
              gestureSeenMs = now;
            }
            // no person channel at all while the wearer's hand is up. tuning the arm filter by
            // size ratio failed both ways - the box wraps hand, forearm and sometimes shoulder,
            // so the ratio ran 1.4x to 19.7x on the same arm
            // the trade: no person warning for the second or two someone is gesturing, standing
            // still, asking a question
            ObjectDetectorResult found = objects.detect(input);
            collectObjects(found, bitmap.getWidth(), bitmap.getHeight());
            if (!handBoxFresh()) {
              float share = largestPerson(found, bitmap.getWidth(), bitmap.getHeight());
              if (share > 0f) {
                personSeenMs = now;
                personFrameShare = share;
              }
            } else {
              personBoxCount = 0;
              lastPersonRaw = 0;
              lastPersonRatio = "hand-up";
            }
          } catch (RuntimeException e) {
            Log.w(TAG, "detection failed", e);
          } finally {
            if (bitmap != null) {
              bitmap.recycle();
            }
            busy.set(false);
          }
        });
  }

  /** true while the L is being held up right now */
  public boolean isGestureVisible() {
    return System.currentTimeMillis() - gestureSeenMs < GESTURE_FRESH_MS;
  }

  /** call after firing, so one continuous hold can't trigger a second request immediately */
  public void consumeGesture() {
    gestureSeenMs = 0;
  }

  public boolean isPersonVisible() {
    return System.currentTimeMillis() - personSeenMs < FRESH_FOR_MS;
  }

  /**
   * how much of the frame the nearest person fills, 0 to 1. we have no metric distance for them -
   * that's the whole point, depth couldn't see them - so apparent size is the only proximity signal
   * available, and it is a weak one: a child at 1 m and an adult at 2 m look alike
   */
  public float personFrameShare() {
    return personFrameShare;
  }

  /**
   * Records where the hand is and reports whether it's the wearer holding up an L. Runs even when
   * the gesture doesn't qualify, because a hand that isn't a shutter is still an arm the person
   * detector is about to trip over.
   */
  private boolean findHand(HandLandmarkerResult result, int width, int height) {
    List<List<NormalizedLandmark>> found = result.landmarks();
    if (found.isEmpty()) {
      lastHandReason = "no-hand";
      return false;
    }
    List<NormalizedLandmark> hand = found.get(0);
    float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
    for (NormalizedLandmark point : hand) {
      minX = Math.min(minX, point.x());
      maxX = Math.max(maxX, point.x());
      minY = Math.min(minY, point.y());
      maxY = Math.max(maxY, point.y());
    }
    handBox.set(minX * width, minY * height, maxX * width, maxY * height);
    handBoxMs = System.currentTimeMillis();

    float handSize = Math.max(maxX - minX, maxY - minY);
    handSeenMs = System.currentTimeMillis();
    lastWorld = result.worldLandmarks().isEmpty() ? null : result.worldLandmarks().get(0);
    lastHandReason = String.format(java.util.Locale.US, "span=%.2f", handSize);

    if (handSize < MIN_HAND_SPAN || lastWorld == null || lastWorld.size() <= MIDDLE_TIP) {
      // apparent size is a fair 2D question - it asks whether the hand is close enough to be the
      // wearer's own, which is genuinely about how big it looks
      lastHandReason += " too-far-or-no-world";
      return false;
    }

    checkPointing(hand);

    // the L, in metres: both digits out and held apart, not merely far from each other
    float spread = worldDistance(lastWorld.get(THUMB_TIP), lastWorld.get(INDEX_TIP));
    float thumbToKnuckle = worldDistance(lastWorld.get(THUMB_TIP), lastWorld.get(MIDDLE_MCP));
    // hard to start, easy to keep. on one continuous hold, accepted frames had a thumb-to-knuckle
    // median of 0.083 and rejected ones 0.068 - the 0.075 threshold sat inside the overlap and
    // chattered 29 accepts against 15 rejects
    // overlapping distributions need two thresholds, not a different one
    boolean holding = System.currentTimeMillis() - gestureSeenMs < GESTURE_FRESH_MS;
    float spreadBar = holding ? L_SPREAD_HOLD_M : L_SPREAD_M;
    float knuckleBar = holding ? THUMB_TUCKED_HOLD_M : THUMB_TUCKED_M;
    boolean isL = spread > spreadBar && thumbToKnuckle > knuckleBar;
    lastHandReason +=
        String.format(
            java.util.Locale.US, " spread=%.3f knuckle=%.3f L=%b", spread, thumbToKnuckle, isL);
    return isL;
  }

  /**
   * Turns a point in the rotated frame back into the un-rotated camera image everything else here
   * speaks. Was hardcoded for a quarter turn in four separate places; now there is one of it and it
   * knows which turn was actually applied.
   */
  private float[] unrotate(float nx, float ny) {
    switch (rotationApplied) {
      case 90:
        return new float[] {ny, 1f - nx};
      case 180:
        return new float[] {1f - nx, 1f - ny};
      case 270:
        return new float[] {1f - ny, nx};
      default:
        return new float[] {nx, ny};
    }
  }

  private float[] unrotateDirection(float dx, float dy) {
    switch (rotationApplied) {
      case 90:
        return new float[] {dy, -dx};
      case 180:
        return new float[] {-dx, -dy};
      case 270:
        return new float[] {-dy, dx};
      default:
        return new float[] {dx, dy};
    }
  }

  private static float worldDistance(Landmark a, Landmark b) {
    float dx = a.x() - b.x();
    float dy = a.y() - b.y();
    float dz = a.z() - b.z();
    return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /**
   * The virtual cane, minus the part that needs depth on the hand.
   *
   * <p>Kim 2014 unprojects the fingertip and casts a real ray; ARCore returns no depth at 45cm and
   * paints the background straight through a palm. So the direction comes from the image instead -
   * knuckle to fingertip, extended. A forehead camera faces where the head faces, so that line is
   * very nearly the line the finger means.
   */
  private void checkPointing(List<NormalizedLandmark> hand) {
    // cALIBRATION: raw world-landmark deltas, unmapped and untouched. Point in three known
    // directions and these numbers say what the axes are, instead of us guessing a convention
    // Google does not document. Delete once the mapping is settled
    Landmark rawBase = lastWorld.get(INDEX_MCP);
    Landmark rawTip = lastWorld.get(INDEX_TIP);
    lastHandReason +=
        String.format(
            java.util.Locale.US,
            " RAW d=(%+.3f,%+.3f,%+.3f)",
            rawTip.x() - rawBase.x(),
            rawTip.y() - rawBase.y(),
            rawTip.z() - rawBase.z());

    float indexLength = worldDistance(lastWorld.get(INDEX_MCP), lastWorld.get(INDEX_TIP));
    float middleLength = worldDistance(lastWorld.get(MIDDLE_MCP), lastWorld.get(MIDDLE_TIP));
    // pointing is the opposite arrangement: index out, middle curled, thumb alongside rather than
    // spread. Same measurement as the L test, read the other way, so the two can never both fire
    float thumbToKnuckle = worldDistance(lastWorld.get(THUMB_TIP), lastWorld.get(MIDDLE_MCP));
    if (thumbToKnuckle > THUMB_TUCKED_M) {
      lastHandReason += String.format(java.util.Locale.US, " thumb-not-tucked=%.3f", thumbToKnuckle);
      return;
    }
    if (indexLength < INDEX_MIN_M || indexLength < POINT_RATIO * middleLength) {
      lastHandReason +=
          String.format(
              java.util.Locale.US,
              " no-point idx=%.3fm mid=%.3fm ratio=%.2f need>%.2f",
              indexLength, middleLength, indexLength / Math.max(1e-4f, middleLength), POINT_RATIO);
      return;
    }

    // direction from the 2D landmarks, ratio from the 3D ones
    // a finger pointing at the camera looks short in the picture but still points the right way,
    // so 2D gets the left-right part right. what it cannot see is how much points at the camera
    // the world landmarks give that, but their axis orientation is undocumented - guessing it
    // produced rays travelling sideways for five metres, so we try both and keep what hits
    NormalizedLandmark base2d = hand.get(INDEX_MCP);
    NormalizedLandmark tip2d = hand.get(INDEX_TIP);
    // un-rotate the 90 degree turn we applied before detection, then flip y because image y runs
    // down and camera y runs up
    float lateralX = tip2d.y() - base2d.y();
    float lateralY = tip2d.x() - base2d.x();
    float lateralLen = (float) Math.sqrt(lateralX * lateralX + lateralY * lateralY);
    if (lateralLen < 1e-5f) {
      lateralX = 0f;
      lateralY = 0f;
    } else {
      lateralX /= lateralLen;
      lateralY /= lateralLen;
    }

    Landmark base = lastWorld.get(INDEX_MCP);
    Landmark tip = lastWorld.get(INDEX_TIP);
    float wx = tip.x() - base.x();
    float wy = tip.y() - base.y();
    float wz = tip.z() - base.z();
    float worldLateral = (float) Math.sqrt(wx * wx + wy * wy);
    float worldDepth = Math.abs(wz);
    float total = (float) Math.sqrt(worldLateral * worldLateral + worldDepth * worldDepth);
    if (total < 1e-5f) {
      lastHandReason += " degenerate-direction";
      return;
    }
    float lateralShare = worldLateral / total;
    float depthShare = worldDepth / total;

    // both candidate directions - forward and backward along the camera axis
    pointingDirection =
        new float[] {lateralX * lateralShare, lateralY * lateralShare, -depthShare};
    pointingDirectionAlt =
        new float[] {lateralX * lateralShare, lateralY * lateralShare, depthShare};

    fingertipImage = unrotate(tip2d.x(), tip2d.y());
    // wrist to fingertip, not knuckle to fingertip. the finger itself is only about 4cm long and
    // shrinks to almost nothing on screen when it points away from the camera, so its direction is
    // mostly noise. wrist to tip is three times longer for the same aim
    NormalizedLandmark wrist2d = hand.get(WRIST);
    pointingImageDir = unrotateDirection(tip2d.x() - wrist2d.x(), tip2d.y() - wrist2d.y());
    pointingSeenMs = System.currentTimeMillis();
    lastHandReason +=
        String.format(
            java.util.Locale.US,
            " POINT lat=(%.2f,%.2f) share=%.2f/%.2f",
            lateralX, lateralY, lateralShare, depthShare);
  }


  /** one line of why, for logcat during bring-up */
  public String diagnostics() {
    return String.format(
        java.util.Locale.US,
        "%s | point=%b gest=%b | person raw=%d rej=%d %s share=%.2f",
        lastHandReason, isPointing(), isGestureVisible(), lastPersonRaw, lastPersonRejected,
        lastPersonRatio + (ownFeet ? " ownFeet" : ""), personFrameShare);
  }

  /**
   * any hand at all, gesture or not. the logs showed "no-hand" on every frame for an entire test
   * session - the wearer was making the gesture below the camera's view and had no way to know.
   * this is what tells them the hand is in frame before they wonder why nothing happens
   */
  public boolean isHandVisible() {
    return System.currentTimeMillis() - handSeenMs < FRESH_FOR_MS;
  }

  public boolean isPointing() {
    return System.currentTimeMillis() - pointingSeenMs < FRESH_FOR_MS;
  }

  /** unit direction of the finger in ARCore camera space */
  public float[] pointingDirection() {
    return pointingDirection;
  }

  /** the same direction with the depth component flipped, for settling the sign empirically */
  public float[] pointingDirectionAlt() {
    return pointingDirectionAlt;
  }

  /** the finger's direction across the picture, un-rotated and un-normalised */
  public float[] pointingImageDir() {
    return pointingImageDir;
  }

  /** fingertip as normalised coordinates in the unrotated camera image */
  public float[] fingertipImage() {
    return fingertipImage;
  }

  /**
   * Keeps every detection, converted out of the rotated frame we detect in and into the coordinates
   * everything else here speaks. The labels come from COCO, which is a blunt eighty-class list -
   * but "chair" is a far more useful thing to hear than "obstacle", and a thing it has never seen
   * simply doesn't get named rather than getting named wrong.
   */
  private void collectObjects(ObjectDetectorResult result, int width, int height) {
    float[] boxes = new float[MAX_OBJECTS * 4];
    String[] labels = new String[MAX_OBJECTS];
    int found = 0;
    for (Detection detection : result.detections()) {
      if (found >= MAX_OBJECTS || detection.categories().isEmpty()) {
        continue;
      }
      Category top = detection.categories().get(0);
      if (top.score() < OBJECT_SCORE || !MOBILITY_CLASSES.contains(top.categoryName())) {
        continue;
      }
      RectF box = detection.boundingBox();
      if (handBoxFresh() && mostlyTheWearersHand(box)) {
        continue;
      }
      float[] c1 = unrotate(box.left / width, box.top / height);
      float[] c2 = unrotate(box.right / width, box.bottom / height);
      boxes[found * 4] = Math.min(c1[0], c2[0]);
      boxes[found * 4 + 1] = Math.min(c1[1], c2[1]);
      boxes[found * 4 + 2] = Math.max(c1[0], c2[0]);
      boxes[found * 4 + 3] = Math.max(c1[1], c2[1]);
      labels[found] = top.categoryName();
      found++;
    }
    objectBoxes = boxes;
    objectLabels = labels;
    objectCount = found;
    StringBuilder names = new StringBuilder();
    for (int i = 0; i < found; i++) {
      names.append(labels[i]).append(' ');
    }
    lastObjectNames = names.length() == 0 ? "-" : names.toString().trim();
  }

  public float[] objectBoxes() {
    return objectBoxes;
  }

  public String[] objectLabels() {
    return objectLabels;
  }

  public int objectCount() {
    return objectCount;
  }

  public String objectNames() {
    return lastObjectNames;
  }

  /** a hand seen this recently is still an arm, whether or not this frame's detector found it */
  private boolean handBoxFresh() {
    return handBoxMs != 0 && System.currentTimeMillis() - handBoxMs < HAND_BOX_STICKY_MS;
  }

  private boolean mostlyTheWearersHand(RectF personBox) {
    if (!handBoxFresh()) {
      return false;
    }
    float left = Math.max(personBox.left, handBox.left);
    float top = Math.max(personBox.top, handBox.top);
    float right = Math.min(personBox.right, handBox.right);
    float bottom = Math.min(personBox.bottom, handBox.bottom);
    if (right <= left || bottom <= top) {
      return false;
    }
    float overlap = (right - left) * (bottom - top);
    float handArea = Math.abs(handBox.width() * handBox.height());
    float personArea = Math.abs(personBox.width() * personBox.height());
    if (handArea <= 0 || personArea <= 0) {
      return false;
    }
    // any person box touching the wearer's own hand is the wearer's own arm
    // measured: a person box on 17 of 17 frames with a hand up, against 5 of 47 without. asking
    // what fraction of the PERSON box was hand never fired at all (the box wraps the forearm);
    // adding a size condition caught only 9 of 17, because the ratio blows past 14x near the lens
    // a real person behind a raised hand is suppressed too - depth still reports them, and a
    // phantom person is a lie
    float handInside = overlap / handArea;
    float sizeRatio = personArea / handArea;
    lastPersonRatio =
        String.format(java.util.Locale.US, "handIn=%.2f size=%.1fx", handInside, sizeRatio);
    return handInside > HAND_OVERLAP_REJECT;
  }

  private float largestPerson(ObjectDetectorResult result, int width, int height) {
    float biggest = 0f;
    float frameArea = width * (float) height;
    float feetBottom = 0f;
    float feetTop = 0f;
    int found = 0;
    int raw = 0;
    int rejected = 0;
    for (Detection detection : result.detections()) {
      for (Category category : detection.categories()) {
        if (!PERSON.equalsIgnoreCase(category.categoryName())
            || category.score() < PERSON_SCORE) {
          continue;
        }
        // boundingBox comes back in pixels of the image we handed in, so the caller passes the
        // frame area and we report a fraction - the absolute pixel count would change meaning the
        // moment we touch the downscale factor
        RectF box = detection.boundingBox();
        raw++;
        if (mostlyTheWearersHand(box)) {
          rejected++;
          continue;
        }
        float share = Math.abs(box.width() * box.height()) / Math.max(1f, frameArea);
        boolean isBiggest = share > biggest;
        if (isBiggest) {
          biggest = share;
        }
        if (found < MAX_BOXES) {
          // undo the 90-degree rotation we applied before detecting: a point (rx, ry) in the
          // rotated frame came from (ry, height_rot - rx) in the original, normalised
          float[] p1 = unrotate(box.left / width, box.top / height);
          float[] p2 = unrotate(box.right / width, box.bottom / height);
          boxBuffer[found * 4] = Math.min(p1[0], p2[0]);
          boxBuffer[found * 4 + 1] = Math.min(p1[1], p2[1]);
          boxBuffer[found * 4 + 2] = Math.max(p1[0], p2[0]);
          boxBuffer[found * 4 + 3] = Math.max(p1[1], p2[1]);
          if (isBiggest) {
            feetBottom = boxBuffer[found * 4 + 3];
            feetTop = boxBuffer[found * 4 + 1];
          }
          found++;
        }
      }
    }
    ownFeet = found > 0 && feetBottom >= FEET_BOTTOM_V && feetTop >= FEET_TOP_V;
    personBoxes = Arrays.copyOf(boxBuffer, boxBuffer.length);
    personBoxCount = found;
    lastPersonRaw = raw;
    lastPersonRejected = rejected;
    return biggest;
  }

  /** person boxes as normalised rects in the unrotated camera image, four floats each */
  public float[] personBoxes() {
    return personBoxes;
  }

  public int personBoxCount() {
    return isPersonVisible() ? personBoxCount : 0;
  }

  public void close() {
    worker.shutdown();
    hands.close();
    objects.close();
  }
}
