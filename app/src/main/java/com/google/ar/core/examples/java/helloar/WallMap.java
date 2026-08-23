/*
 * The Eye - walls, from ARCore's own plane tracker.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import java.util.Collection;
import java.util.Locale;

/**
 * Walls, treated as a different kind of thing from objects.
 *
 * <p>Depth-from-motion is worst at exactly the surfaces that matter most. A painted wall has no
 * texture to match between frames, so it returns almost nothing - and the fan's rule was that a
 * bearing with too few returns is free. Absence of evidence became evidence of absence, and the
 * device routed people into the walls it could not see while carefully avoiding the backpack it
 * could.
 *
 * <p>ARCore already solves this and we were not asking. Its plane tracker fits large flat surfaces
 * over many frames and keeps them in its own map, so a wall stays known after you look away. That
 * is the property objects do not have and the reason walls get their own channel: an object is a
 * momentary detection, a wall is a fact about the room.
 *
 * <p>Each fan bearing is intersected with every tracked vertical plane. It is a ray-plane test,
 * which is one dot product per plane per bearing.
 */
public class WallMap {

  private static final float MIN_M = 0.30f;
  private static final float MAX_M = 5.00f;

  /** ARCore's planes are already fitted over many frames, so they are worth more than one pixel */
  private static final float WALL_WEIGHT = 1.0f;

  private int wallsSeen = 0;
  private int bearingsBlocked = 0;

  /**
   * Cast every fan bearing against the tracked walls and record what it hits.
   *
   * @param cameraPose where the wearer is, in ARCore's world
   * @param cameraYawDeg where the head points, in the same world
   * @param fan the room map that routing consults
   */
  public void update(
      Collection<Plane> planes,
      Pose cameraPose,
      float cameraYawDeg,
      float halfSpanDeg,
      float stepDeg,
      WorldFan fan,
      long nowMs) {
    wallsSeen = 0;
    bearingsBlocked = 0;
    if (planes == null) {
      return;
    }

    float cx = cameraPose.tx();
    float cy = cameraPose.ty();
    float cz = cameraPose.tz();

    // Build the ray from the camera's OWN axes rather than from an angle.
    //
    // Going through a yaw and back out through sin/cos means picking a sign convention twice and
    // agreeing with yourself both times. The first version did not: it used dz = -cos where the
    // yaw was defined as atan2(-z0, -z2), which places every wall 180 degrees from where it is.
    // forward and right come straight out of the pose, so there is no convention left to get wrong,
    // and a positive offset is to the wearer's right for the same reason pointX is.
    float[] zAxis = cameraPose.getZAxis();
    float[] xAxis = cameraPose.getXAxis();
    float fx = -zAxis[0];
    float fz = -zAxis[2];
    float rx = xAxis[0];
    float rz = xAxis[2];
    float fLen = (float) Math.sqrt(fx * fx + fz * fz);
    float rLen = (float) Math.sqrt(rx * rx + rz * rz);
    if (fLen < 1e-4f || rLen < 1e-4f) {
      // the phone is pointing straight up or straight down; there is no forward to speak of
      return;
    }
    fx /= fLen;
    fz /= fLen;
    rx /= rLen;
    rz /= rLen;

    for (float offset = -halfSpanDeg; offset <= halfSpanDeg; offset += stepDeg) {
      float worldYaw = cameraYawDeg + offset;
      double rad = Math.toRadians(offset);
      float dx = (float) (fx * Math.cos(rad) + rx * Math.sin(rad));
      float dz = (float) (fz * Math.cos(rad) + rz * Math.sin(rad));

      float nearest = Float.MAX_VALUE;
      for (Plane plane : planes) {
        if (plane.getTrackingState() != TrackingState.TRACKING
            || plane.getType() != Plane.Type.VERTICAL
            || plane.getSubsumedBy() != null) {
          continue;
        }
        Pose centre = plane.getCenterPose();
        float[] normal = centre.getYAxis();
        float denom = dx * normal[0] + dz * normal[2];
        if (Math.abs(denom) < 1e-4f) {
          // the ray runs along the wall rather than into it
          continue;
        }
        float toPlaneX = centre.tx() - cx;
        float toPlaneY = centre.ty() - cy;
        float toPlaneZ = centre.tz() - cz;
        float t = (toPlaneX * normal[0] + toPlaneY * normal[1] + toPlaneZ * normal[2]) / denom;
        if (t < MIN_M || t > MAX_M || t >= nearest) {
          continue;
        }
        // The plane is infinite, the wall is not, so the hit has to land on the fitted patch.
        //
        // isPoseInPolygon was too strict: a logged run tracked 1 to 4 walls on 47 frames and
        // blocked a bearing on 4 of them. ARCore fits vertical planes as small ragged patches and
        // grows them slowly, so an exact polygon test rejects hits on wall that is plainly there.
        // The rectangular extent is the looser bound and is what this needs.
        Pose hit = Pose.makeTranslation(cx + dx * t, centre.ty(), cz + dz * t);
        if (!plane.isPoseInExtents(hit)) {
          continue;
        }
        nearest = t;
      }

      if (nearest < Float.MAX_VALUE) {
        bearingsBlocked++;
        fan.observe(worldYaw, nearest, WALL_WEIGHT, nowMs);
      }
    }

    for (Plane plane : planes) {
      if (plane.getTrackingState() == TrackingState.TRACKING
          && plane.getType() == Plane.Type.VERTICAL
          && plane.getSubsumedBy() == null) {
        wallsSeen++;
      }
    }
  }

  public String diagnostics() {
    return String.format(Locale.US, "walls=%d blocked=%d", wallsSeen, bearingsBlocked);
  }
}
