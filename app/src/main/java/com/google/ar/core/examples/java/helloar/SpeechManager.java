/*
 * The Eye - spoken output.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

/**
 * Speech, rationed. Blind pedestrians need their hearing for traffic, footsteps and echoes, and a
 * system that says "obstacle ahead" thirty times a second is an alarm, not an aid.
 *
 * <p>The rule that does the work: <b>vibration carries state, speech carries change.</b> A wall
 * two metres ahead buzzes for as long as it is there and is named exactly once.
 *
 * <p>On-device, Android's own text-to-speech, no network.
 */
public class SpeechManager {

  /** higher wins. a lower-priority line never interrupts a higher one that's still being said */
  public enum Level {
    /** an answer the wearer explicitly asked for - always gets through, always flushes */
    REQUESTED(100),
    /** the ground falling away */
    CRITICAL(80),
    /** something in the collision corridor, or a person depth can't measure */
    HIGH(60),
    /** a step up, or an admission that we can't see */
    MEDIUM(40),
    /** housekeeping */
    LOW(20);

    final int rank;

    Level(int rank) {
      this.rank = rank;
    }
  }

  // nothing is urgent enough to be worth saying twice inside two seconds. haptics are already
  // carrying the urgency; this is for naming the thing
  private static final long MIN_GAP_MS = 5000;

  // the same sentence again only after this long, even if the state technically re-entered. stops
  // a threshold flickering across its boundary from producing a stutter
  private static final long REPEAT_COOLDOWN_MS = 25000;

  // the same SITUATION again only after this long. walking towards a chair is one situation from
  // four metres to one, however many different sentences describe it on the way
  private static final long SITUATION_REPEAT_MS = 20000;

  // not final: the init callback reads it, and javac won't accept a final field being read inside
  // the constructor that assigns it. volatile because that callback arrives on another thread
  private volatile TextToSpeech tts;
  private volatile boolean ready = false;

  // what situation the last line described, which is not the same thing as the words used.
  // "obstacle 1.5 metres on your left" and "obstacle 1.0 metres straight ahead" are different
  // strings and the same situation, so comparing strings let the corridor talk every 5 seconds
  // for as long as anything was in front of the wearer
  private String lastKey = "";

  private String lastSpoken = "";
  private long lastSpokenMs = 0;
  private Level lastLevel = Level.LOW;

  public SpeechManager(Context context) {
    tts =
        new TextToSpeech(
            context,
            status -> {
              ready = status == TextToSpeech.SUCCESS;
              if (ready) {
                // English. we tried Romanian and the device's Romanian voice was bad enough
                // that the words were harder to make out than a foreign language spoken clearly
                tts.setLanguage(Locale.UK);
                // slightly quick. these are short phrases and a slow voice is a voice you talk over
                tts.setSpeechRate(1.15f);
              }
            });
  }

  /**
   * @return true if it was actually said
   */
  public boolean announce(Level level, String text) {
    return announce(level, text, text);
  }

  /**
   * @param situationKey what this line is ABOUT, coarsely. Two lines sharing a key are the same
   *     news told twice and the second one is not said.
   */
  public boolean announce(Level level, String text, String situationKey) {
    if (!ready || text == null || text.isEmpty()) {
      return false;
    }
    long now = System.currentTimeMillis();

    if (level == Level.REQUESTED) {
      // the wearer asked. cut off whatever is being said and answer them
      lastKey = situationKey;
      say(text, level, now, TextToSpeech.QUEUE_FLUSH);
      return true;
    }

    if (tts.isSpeaking() && level.rank <= lastLevel.rank) {
      return false;
    }
    if (now - lastSpokenMs < MIN_GAP_MS) {
      return false;
    }
    if (text.equals(lastSpoken) && now - lastSpokenMs < REPEAT_COOLDOWN_MS) {
      return false;
    }
    if (situationKey.equals(lastKey) && now - lastSpokenMs < SITUATION_REPEAT_MS) {
      return false;
    }
    lastKey = situationKey;
    say(text, level, now, TextToSpeech.QUEUE_FLUSH);
    return true;
  }

  private void say(String text, Level level, long now, int queueMode) {
    lastSpoken = text;
    lastSpokenMs = now;
    lastLevel = level;
    tts.speak(text, queueMode, null, "reach");
  }

  /** true while the phone is talking. the voice recogniser has to be deaf for exactly this long */
  public boolean isSpeaking() {
    return tts != null && ready && tts.isSpeaking();
  }

  public void stop() {
    if (tts != null) {
      tts.stop();
    }
  }

  public void close() {
    if (tts != null) {
      tts.stop();
      tts.shutdown();
    }
  }
}
