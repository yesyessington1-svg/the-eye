/*
 * Reach - camera image conversion for MediaPipe.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * ARCore hands out YUV_420_888, MediaPipe wants a Bitmap, nothing in the platform bridges the two.
 *
 * <p>Split in two: {@link #grab} only copies bytes and must run on the thread owning the
 * Image; {@link #decode} does the JPEG round-trip anywhere. Doing both on the render thread killed
 * depth once the CPU image went past VGA - raw validity fell from a 61-99% band to a median of
 * zero, because ARCore stopped getting continuous frames.
 */
public class YuvToBitmap {

  private final ByteArrayOutputStream jpegBuffer = new ByteArrayOutputStream();
  private byte[] nv21 = new byte[0];

  /** an NV21 copy of one camera frame, safe to hand to another thread */
  public static final class Frame {
    final byte[] nv21;
    final int width;
    final int height;

    Frame(byte[] nv21, int width, int height) {
      this.nv21 = nv21;
      this.width = width;
      this.height = height;
    }
  }

  /**
   * @param downscale 1 for full size, 2 for half, and so on
   * @param rotationDegrees ARCore hands out frames in sensor orientation, which is landscape. with
   *     the phone upright a hand held up arrives on its side, and detectors are measurably worse on
   *     rotated input - so we straighten it here rather than hoping the model copes
   */
  public Bitmap convert(Image image, int downscale, int rotationDegrees) {
    return decode(grab(image), downscale, rotationDegrees);
  }

  /**
   * Copy the planes out. Cheap - two memcpy-shaped loops and nothing else - so it is the only part
   * that has to happen while the Image is still open.
   */
  public Frame grab(Image image) {
    int width = image.getWidth();
    int height = image.getHeight();
    int needed = width * height * 3 / 2;
    if (nv21.length != needed) {
      nv21 = new byte[needed];
    }

    Image.Plane[] planes = image.getPlanes();
    ByteBuffer yBuffer = planes[0].getBuffer();
    ByteBuffer uBuffer = planes[1].getBuffer();
    ByteBuffer vBuffer = planes[2].getBuffer();

    // luma first, row by row, because rowStride is usually wider than the image
    int yRowStride = planes[0].getRowStride();
    int offset = 0;
    for (int row = 0; row < height; row++) {
      yBuffer.position(row * yRowStride);
      yBuffer.get(nv21, offset, width);
      offset += width;
    }

    // NV21 wants chroma interleaved as VUVUVU. the source planes are usually already interleaved
    // with pixelStride 2, which is why we walk them by stride instead of copying blocks
    int uvRowStride = planes[1].getRowStride();
    int uvPixelStride = planes[1].getPixelStride();
    for (int row = 0; row < height / 2; row++) {
      for (int col = 0; col < width / 2; col++) {
        int uvIndex = row * uvRowStride + col * uvPixelStride;
        nv21[offset++] = vBuffer.get(uvIndex);
        nv21[offset++] = uBuffer.get(uvIndex);
      }
    }

    return new Frame(nv21, width, height);
  }

  /** the expensive half: JPEG out, bitmap in, straighten. safe to run off the render thread */
  public Bitmap decode(Frame frame, int downscale, int rotationDegrees) {
    if (frame == null) {
      return null;
    }
    int width = frame.width;
    int height = frame.height;
    jpegBuffer.reset();
    YuvImage yuv = new YuvImage(frame.nv21, ImageFormat.NV21, width, height, null);
    yuv.compressToJpeg(new Rect(0, 0, width, height), 85, jpegBuffer);
    byte[] jpeg = jpegBuffer.toByteArray();

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = Math.max(1, downscale);
    Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
    if (rotationDegrees == 0 || decoded == null) {
      return decoded;
    }
    Matrix rotation = new Matrix();
    rotation.postRotate(rotationDegrees);
    Bitmap rotated =
        Bitmap.createBitmap(
            decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), rotation, true);
    if (rotated != decoded) {
      decoded.recycle();
    }
    return rotated;
  }
}
