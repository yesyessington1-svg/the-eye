/*
 * Reach - what the finger is pointing at, decided in the picture.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

/**
 * Which detected object the wearer is pointing at.
 *
 * <p>A real 3D raycast needed four things we could not get: the fingertip's position in space
 * (depth cannot see a hand), the finger's true direction (axes nobody documents), a
 * trustworthy depth map along the whole path, and thresholds for all of it at once.
 *
 * <p>This asks a smaller question: does the line from knuckle through fingertip, drawn on the
 * picture, cross a box we recognise? No intrinsics, no gravity, no 3D. Distance comes from one
 * depth sample at the middle of the box that was hit.
 */
public class PointedObject {

  // below this the finger is aimed too close to the camera's own axis for its direction on screen
  // to mean anything - a finger pointing where the head already faces is a dot in the picture
  private static final float MIN_DIRECTION = 0.03f;

  // how far off the finger's line a box may sit and still count, in fractions of the image
  // scoring by ANGLE favoured far-away objects: the further a thing is, the smaller the angle for
  // the same sideways miss, so it kept picking things across the room
  private static final float MAX_OFF_AXIS = 0.18f;

  // pointing forward can't be read as a direction, so we take it literally: the thing in the middle
  // of the view. The camera rides on the forehead, so the middle of the picture is where the head
  // is already facing
  private static final float CENTRE_TOLERANCE = 0.22f;

  public static final class Result {
    public final boolean found;
    public final String label;
    /** centre of the box that was hit, in normalised image coordinates */
    public final float centreU;
    public final float centreV;
    public final int index;
    /** true when we fell back to "whatever is straight ahead" instead of following the finger */
    public final boolean usedCentre;

    Result(boolean found, String label, float centreU, float centreV, int index, boolean usedCentre) {
      this.found = found;
      this.label = label;
      this.centreU = centreU;
      this.centreV = centreV;
      this.index = index;
      this.usedCentre = usedCentre;
    }
  }

  private static final Result NOTHING = new Result(false, "", 0f, 0f, -1, false);

  /**
   * Picks the box nearest the finger's line, not the first one the line runs into - marching along
   * the line returned the laptop when aiming at the table under it.
   */
  public static Result find(
      float tipU, float tipV, float dirU, float dirV, float[] boxes, String[] labels, int count) {
    if (count <= 0) {
      return NOTHING;
    }
    float length = (float) Math.sqrt(dirU * dirU + dirV * dirV);
    if (length < MIN_DIRECTION) {
      return nearestToCentre(boxes, labels, count);
    }
    dirU /= length;
    dirV /= length;

    int best = -1;
    float bestOffAxis = Float.MAX_VALUE;
    for (int i = 0; i < count; i++) {
      float cu = (boxes[i * 4] + boxes[i * 4 + 2]) / 2f;
      float cv = (boxes[i * 4 + 1] + boxes[i * 4 + 3]) / 2f;
      float toU = cu - tipU;
      float toV = cv - tipV;
      float along = toU * dirU + toV * dirV;
      if (along <= 0.02f) {
        // behind the finger, or on top of it
        continue;
      }
      // perpendicular distance from the line, allowing for the box's own width - a wide sofa
      // slightly off axis is still the thing being pointed at
      float offAxis = Math.abs(toU * dirV - toV * dirU);
      float halfWidth = Math.max(boxes[i * 4 + 2] - boxes[i * 4], boxes[i * 4 + 3] - boxes[i * 4 + 1]) / 2f;
      offAxis = Math.max(0f, offAxis - halfWidth);
      if (offAxis > MAX_OFF_AXIS) {
        continue;
      }
      if (offAxis < bestOffAxis) {
        bestOffAxis = offAxis;
        best = i;
      }
    }
    if (best < 0) {
      // nothing within the cone. saying nothing is right - an object detector that has never seen
      // a radiator should not be made to guess at one
      return NOTHING;
    }
    return new Result(
        true,
        labels[best] == null ? "something" : labels[best],
        (boxes[best * 4] + boxes[best * 4 + 2]) / 2f,
        (boxes[best * 4 + 1] + boxes[best * 4 + 3]) / 2f,
        best,
        false);
  }

  private static Result nearestToCentre(float[] boxes, String[] labels, int count) {
    int best = -1;
    float bestDistance = Float.MAX_VALUE;
    for (int i = 0; i < count; i++) {
      float cu = (boxes[i * 4] + boxes[i * 4 + 2]) / 2f;
      float cv = (boxes[i * 4 + 1] + boxes[i * 4 + 3]) / 2f;
      float distance = (float) Math.hypot(cu - 0.5f, cv - 0.5f);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = i;
      }
    }
    if (best < 0 || bestDistance > CENTRE_TOLERANCE) {
      return NOTHING;
    }
    return new Result(
        true,
        labels[best] == null ? "something" : labels[best],
        (boxes[best * 4] + boxes[best * 4 + 2]) / 2f,
        (boxes[best * 4 + 1] + boxes[best * 4 + 3]) / 2f,
        best,
        true);
  }

}
