/*
 * Reach - learned depth, for everywhere ARCore has none.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.tensorflow.lite.Interpreter;

/**
 * A second opinion about depth, from a model instead of from motion.
 *
 * <p>Depth-from-motion on a phone with no depth sensor is blind at 45cm, freezes for up to a
 * minute, and gives nothing for eleven seconds from cold. Depth Anything V2 has none of those
 * failure modes - it reads one picture - but its output is relative, not metres.
 *
 * <p>ARCore knows metres where it can see; this knows shape everywhere. Fitting a scale and offset
 * on the overlap makes the relative map metric, the same fitting step used by HybridDepth
 * (arXiv:2407.18443).
 */
public class MonoDepth {

  private static final String TAG = "ReachMono";
  private static final String MODEL = "depth_anything_v2.tflite";

  /** the network's fixed input size */
  public static final int SIZE = 518;

  // a coarse grid is plenty for a two-parameter fit, and each point costs a coordinate transform
  public static final int FIT_GRID = 20;
  private static final int MIN_FIT_POINTS = 60;

  private final Interpreter interpreter;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy = new AtomicBoolean(false);

  private final ByteBuffer input =
      ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder());
  private final float[][][][] output = new float[1][SIZE][SIZE][1];
  private final int[] pixels = new int[SIZE * SIZE];

  /** the most recent relative map, and how long it took */
  private volatile float[] latest = null;
  private volatile long latestMs = 0;
  private volatile long lastInferenceMs = 0;

  // scale and offset that turn the relative map into metres, fitted against ARCore
  private volatile float scale = Float.NaN;
  private volatile float offset = Float.NaN;
  private volatile int fitPoints = 0;
  private volatile float correlation = Float.NaN;

  public MonoDepth(Context context) throws IOException {
    Interpreter.Options options = new Interpreter.Options();
    // xNNPACK, explicitly. Without it this ran at 2.4 seconds a frame on an 8 Elite - about
    // 28 times slower than Qualcomm's published number for a chip a generation older, which is
    // what unaccelerated float matmul on a 518x518 vision transformer costs
    options.setUseXNNPACK(true);
    options.setNumThreads(4);
    interpreter = new Interpreter(loadModel(context), options);
  }

  private static MappedByteBuffer loadModel(Context context) throws IOException {
    AssetFileDescriptor fd = context.getAssets().openFd(MODEL);
    try (FileInputStream stream = new FileInputStream(fd.getFileDescriptor())) {
      return stream
          .getChannel()
          .map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }
  }

  /**
   * The model's map is in rotated space; every coordinate we hold is in ARCore's un-rotated image
   * space. Sampling one with the other compares different halves of the room - correlation of
   * exactly zero until we spotted it. A point (u, v) sits at (1 - v, u) after the rotation.
   */
  private static int mapX(float u, float v) {
    return Math.max(0, Math.min(SIZE - 1, (int) ((1f - v) * SIZE)));
  }

  private static int mapY(float u, float v) {
    return Math.max(0, Math.min(SIZE - 1, (int) (u * SIZE)));
  }

  public boolean isBusy() {
    return busy.get();
  }

  public long lastInferenceMs() {
    return lastInferenceMs;
  }

  /** hand over a camera frame; the result appears later on {@link #relativeMap()} */
  public void offer(Bitmap frame) {
    if (!busy.compareAndSet(false, true)) {
      frame.recycle();
      return;
    }
    worker.execute(
        () -> {
          try {
            Bitmap scaled = Bitmap.createScaledBitmap(frame, SIZE, SIZE, true);
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
            if (scaled != frame) {
              scaled.recycle();
            }
            input.rewind();
            for (int pixel : pixels) {
              // the model wants plain 0..1 RGB - no ImageNet normalisation, per Qualcomm's metadata
              input.putFloat(((pixel >> 16) & 0xFF) / 255f);
              input.putFloat(((pixel >> 8) & 0xFF) / 255f);
              input.putFloat((pixel & 0xFF) / 255f);
            }
            input.rewind();
            long started = System.currentTimeMillis();
            interpreter.run(input, output);
            lastInferenceMs = System.currentTimeMillis() - started;

            float[] flat = new float[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) {
              for (int x = 0; x < SIZE; x++) {
                flat[y * SIZE + x] = output[0][y][x][0];
              }
            }
            latest = flat;
            latestMs = System.currentTimeMillis();
          } catch (Exception e) {
            Log.w(TAG, "inference failed", e);
          } finally {
            frame.recycle();
            busy.set(false);
          }
        });
  }

  public float[] relativeMap() {
    return latest;
  }

  public long mapAgeMs() {
    return latestMs == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - latestMs;
  }

  /**
   * Fit metres onto the relative map, using ARCore's own depth as the ruler.
   *
   * <p>The model gets the shape right and the units wrong. Two
   * unknowns, a scale and an offset, and ARCore hands us thousands of samples of ground truth every
   * frame in the places where it can see. Least squares over the overlap, then the same two numbers
   * apply everywhere else, including the places ARCore is blind.
   */
  public void fitTo(Image arcoreDepth, float[] texCoords, float[] imageCoords, int count) {
    float[] map = latest;
    if (map == null) {
      return;
    }
    int width = arcoreDepth.getWidth();
    int height = arcoreDepth.getHeight();
    ShortBuffer depth =
        arcoreDepth.getPlanes()[0].getBuffer().order(ByteOrder.nativeOrder()).asShortBuffer();

    double sumX = 0, sumY = 0, sumXX = 0, sumXY = 0;
    int n = 0;
    for (int i = 0; i < count; i++) {
      float tu = texCoords[i * 2];
      float tv = texCoords[i * 2 + 1];
      float iu = imageCoords[i * 2];
      float iv = imageCoords[i * 2 + 1];
      if (tu < 0 || tu >= 1 || tv < 0 || tv >= 1 || iu < 0 || iu >= 1 || iv < 0 || iv >= 1) {
        continue;
      }
      int dx = Math.min(width - 1, (int) (tu * width));
      int dy = Math.min(height - 1, (int) (tv * height));
      int millimeters = depth.get(dy * width + dx) & 0xFFFF;
      if (millimeters == 0 || millimeters > 6000) {
        continue;
      }
      double relative = map[mapY(iu, iv) * SIZE + mapX(iu, iv)];
      double metres = millimeters / 1000.0;
      sumX += relative;
      sumY += metres;
      sumXX += relative * relative;
      sumXY += relative * metres;
      n++;
    }
    fitPoints = n;
    if (n < MIN_FIT_POINTS) {
      return;
    }
    double denominator = n * sumXX - sumX * sumX;
    if (Math.abs(denominator) < 1e-9) {
      return;
    }
    double a = (n * sumXY - sumX * sumY) / denominator;
    double b = (sumY - a * sumX) / n;
    // correlation, so the log says whether the two maps agree at all rather than only what line
    // was drawn through them. our first attempt paired pixels across three different coordinate
    // systems and fitted a slope of zero - a flat line through noise, reported as a depth of 2.07m
    // no matter what the camera was looking at
    double meanX = sumX / n;
    double meanY = sumY / n;
    double varX = sumXX / n - meanX * meanX;
    double covXY = sumXY / n - meanX * meanY;
    double varY = 0;
    correlation = (float) (covXY / Math.sqrt(Math.max(1e-12, varX)) / Math.max(1e-6, Math.sqrt(Math.max(1e-12, sumY * sumY / n - meanY * meanY))));
    scale = (float) a;
    offset = (float) b;
  }

  /** metres at a normalised point, or NaN when we have no fit yet */
  public float metresAt(float u, float v) {
    float[] map = latest;
    if (map == null || Float.isNaN(scale)) {
      return Float.NaN;
    }
    float metres = scale * map[mapY(u, v) * SIZE + mapX(u, v)] + offset;
    if (metres <= 0.05f || metres > 12f) {
      return Float.NaN;
    }
    return metres;
  }

  public String diagnostics() {
    float[] map = latest;
    if (map == null) {
      return "mono: no map yet";
    }
    float min = Float.MAX_VALUE;
    float max = -Float.MAX_VALUE;
    for (int i = 0; i < map.length; i += 97) {
      min = Math.min(min, map[i]);
      max = Math.max(max, map[i]);
    }
    return String.format(
        java.util.Locale.US,
        "mono %dms age=%dms raw[%.2f..%.2f] fit=%d r=%.2f scale=%.3f off=%.3f centre=%.2fm",
        lastInferenceMs, mapAgeMs(), min, max, fitPoints, correlation, scale, offset,
        metresAt(0.5f, 0.5f));
  }

  public void close() {
    worker.shutdown();
    interpreter.close();
  }
}
