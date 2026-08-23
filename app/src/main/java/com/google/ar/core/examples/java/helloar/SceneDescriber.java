/*
 * The Eye - scene description for the portal gesture.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Depth tells you how far; this tells you what.
 *
 * <p>The only part of The Eye that leaves the device, so it sits off the safety path -
 * nothing here can stop you walking into anything. It answers a question the wearer chose to ask.
 *
 * <p>Hand-rolled HTTP rather than a vendor SDK: those are built for servers and drag desugaring,
 * duplicate META-INF entries and megabytes of dex onto Android for one POST.
 */
public class SceneDescriber {

  private static final String TAG = "EyeDescriber";
  private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

  // small vision model. the user is standing still waiting for this, so latency is the
  // spec - a better description that arrives three seconds later is a worse description
  private static final String MODEL = "gpt-4o-mini";

  // a mobility aid, not a photo captioner - long descriptions are useless to someone waiting to
  // move
  // an earlier prompt offered "say I can't make that out if unclear" and the model handed that
  // back for a coffee table, a door, a television and a person. give a small model an escape
  // hatch and it takes it, so the only refusal left is for a genuinely black frame
  private static final String SYSTEM_PROMPT =
      "You are the eyes of a blind person wearing a camera on their chest or forehead. "
          + "They have asked whether they can walk forward. "
          + "Start with exactly one word: GO, CAREFUL or STOP. "
          + "STOP means something is in their path within about a step. "
          + "CAREFUL means something is in their path but further off, or the floor changes. "
          + "GO means the way ahead is genuinely empty. "
          + "Then ONE short sentence, under 10 words, naming the single most important thing and "
          + "where it is: ahead, on the left, on the right, low, or overhead. "
          + "Never state a distance in metres, centimetres, feet or steps. You cannot measure "
          + "distance from one photograph and a wrong number is worse than none. "
          + "A sensor reading may be given to help you judge; the sensor cannot see glass, water "
          + "bottles or anything clear, so trust your eyes when it says nothing is there. "
          + "The wearer's own hand is usually in the foreground making an L shape - that is the "
          + "button they pressed to ask you, not something in their path. Never mention their "
          + "hand, their fingers, or any gesture. "
          + "Always name something concrete. Never say the image is unclear and never ask for "
          + "another photo. Never mention the camera, the photo, or that you are a model. "
          + "Only if the image is completely black, say: STOP. Nothing visible.";

  /**
   * The fallback prompt. Deliberately not the scene description - when depth has died the wearer
   * does not need prose, they need the one fact the dead sensor was supposed to supply. A closed
   * set of answers also means we can act on it rather than just read it aloud.
   */
  private static final String FALLBACK_PROMPT =
      "You are the backup eyes of a blind person walking, worn on their forehead. "
          + "Their depth sensor has failed and you are covering for it. "
          + "Answer ONLY whether something is in their way within about two metres straight ahead. "
          + "Reply with exactly one of these forms and nothing else: "
          + "CLEAR | OBSTACLE <two words> | PERSON | STAIRS | DROP | DOORWAY. "
          + "Judge distance from how much of the frame the thing fills. "
          + "If unsure between clear and an obstacle, say the obstacle.";

  public enum Mode {
    /** the wearer asked - full sentence, spoken back to them */
    SCENE,
    /** depth died and we are standing in for it - one closed-set answer */
    FALLBACK
  }

  public interface Callback {
    void onDescription(String text);
  }

  // tEMPORARY, for bring-up only: writes the exact bytes we send to the model into the app's
  // external files dir so we can pull them with adb and look. delete once descriptions work
  private static final boolean DUMP_SENT_IMAGE = true;

  private final Context appContext;
  private final String apiKey;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean inFlight = new AtomicBoolean(false);

  public SceneDescriber(Context context) {
    appContext = context.getApplicationContext();
    apiKey = BuildConfig.OPENAI_API_KEY;
  }

  public boolean isConfigured() {
    return !apiKey.isEmpty();
  }

  /** true while a request is out, so the gesture can't fire a second one on top of the first */
  public boolean isBusy() {
    return inFlight.get();
  }

  /**
   * @param crop the region the user framed, already cropped. we send the crop rather than the whole
   *     frame because the framing IS the question - and a small image is faster and cheaper
   */
  public void describe(Bitmap crop, Callback callback) {
    describe(crop, Mode.SCENE, "", callback);
  }

  public void describe(Bitmap crop, Mode mode, Callback callback) {
    describe(crop, mode, "", callback);
  }

  public void describe(Bitmap crop, Mode mode, String depthNote, Callback callback) {
    if (!inFlight.compareAndSet(false, true)) {
      return;
    }
    if (apiKey.isEmpty()) {
      inFlight.set(false);
      callback.onDescription("No API key set");
      return;
    }
    worker.execute(
        () -> {
          try {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            crop.compress(Bitmap.CompressFormat.JPEG, 80, jpeg);
            byte[] bytes = jpeg.toByteArray();
            if (DUMP_SENT_IMAGE) {
              dump(bytes);
            }
            String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
            Log.i(TAG, "sending " + crop.getWidth() + "x" + crop.getHeight()
                + ", " + bytes.length + " bytes of jpeg");
            String answer = request(encoded, mode, depthNote);
            callback.onDescription(answer);
          } catch (Exception e) {
            // venue wifi is the likeliest failure and the user has to be able to tell "nothing
            // there" apart from "couldn't ask"
            Log.w(TAG, "description failed", e);
            callback.onDescription("No network");
          } finally {
            crop.recycle();
            inFlight.set(false);
          }
        });
  }

  private String request(String base64Jpeg, Mode mode, String depthNote) throws Exception {
    JSONObject image = new JSONObject();
    image.put("type", "image_url");
    // "low" detail keeps this to a single small tile. our crop is a few hundred pixels of one
    // object, so the high-detail tiling would cost latency for nothing
    image.put(
        "image_url",
        new JSONObject()
            .put("url", "data:image/jpeg;base64," + base64Jpeg)
            // "low" resamples to 512 square before the model ever sees it, which throws away
            // exactly the small floor clutter this exists to catch
            .put("detail", mode == Mode.FALLBACK ? "low" : "high"));

    JSONArray userContent = new JSONArray();
    userContent.put(
        new JSONObject()
            .put("type", "text")
            .put(
                "text",
                mode == Mode.FALLBACK
                    ? "Is anything in my way within two metres?"
                    : depthNote.isEmpty()
                        ? "What is in front of me?"
                        : "What is in front of me? " + depthNote));
    userContent.put(image);

    JSONArray messages = new JSONArray();
    messages.put(
        new JSONObject()
            .put("role", "system")
            .put("content", mode == Mode.FALLBACK ? FALLBACK_PROMPT : SYSTEM_PROMPT));
    messages.put(new JSONObject().put("role", "user").put("content", userContent));

    JSONObject body = new JSONObject();
    body.put("model", MODEL);
    body.put("max_tokens", 60);
    body.put("messages", messages);

    HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
    try {
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Authorization", "Bearer " + apiKey);
      connection.setDoOutput(true);
      // the user is standing still with a hand in the air. if it hasn't answered in eight seconds
      // the answer has stopped being useful
      connection.setConnectTimeout(4000);
      connection.setReadTimeout(8000);

      try (OutputStream out = connection.getOutputStream()) {
        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
      }

      int status = connection.getResponseCode();
      InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String response = readAll(stream);
      if (status >= 400) {
        Log.w(TAG, "http " + status + ": " + response);
        return status == 401 ? "API key rejected" : "Service error " + status;
      }
      String text =
          new JSONObject(response)
              .getJSONArray("choices")
              .getJSONObject(0)
              .getJSONObject("message")
              .getString("content")
              .trim();
      return text.isEmpty() ? "No answer came back" : text;
    } finally {
      connection.disconnect();
    }
  }

  private static String readAll(InputStream stream) throws Exception {
    if (stream == null) {
      return "";
    }
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int read;
    while ((read = stream.read(chunk)) != -1) {
      buffer.write(chunk, 0, read);
    }
    return buffer.toString("UTF-8");
  }

  private void dump(byte[] jpeg) {
    try {
      File out = new File(appContext.getExternalFilesDir(null), "last_sent.jpg");
      try (FileOutputStream stream = new FileOutputStream(out)) {
        stream.write(jpeg);
      }
      Log.i(TAG, "wrote " + out.getAbsolutePath());
    } catch (Exception e) {
      Log.w(TAG, "dump failed", e);
    }
  }

  public void close() {
    worker.shutdown();
  }
}
