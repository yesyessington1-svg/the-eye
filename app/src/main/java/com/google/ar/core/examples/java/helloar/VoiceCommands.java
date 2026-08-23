/*
 * Reach - spoken control.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice control, because a mobility aid you drive by finding a button on a screen is a contradiction.
 *
 * <p>Everything the app can do is reachable by saying it. The mode pill stays because a sighted
 * judge watching a mirrored screen needs something to look at, but nothing here requires it.
 *
 * <p>Listening restarts itself. Android's recogniser ends a session on every result, every silence
 * and every error, so continuous listening means starting a new one each time - and it must be
 * paused while our own text-to-speech is talking, or the app hears itself say "guardian" and
 * switches mode.
 */
public class VoiceCommands {

  private static final String TAG = "ReachVoice";

  public enum Command {
    MODE_SCENE,
    MODE_GUARDIAN,
    MODE_APERTURE,
    MODE_AUTO,
    DESCRIBE,
    HELP,
    QUIET
  }

  public interface Listener {
    void onCommand(Command command);
  }

  // one word each, none of them a word the app says back at itself, plus the things the recogniser
  // actually mishears them as. "garden" for guardian and "cap" for gap are not jokes - a
  // recogniser tuned for English sentences does that to a single shouted word, and the wearer
  // should not have to learn to enunciate for it
  private static final String[][] KEYWORDS = {
    {"scene", "seen", "photo", "picture"},
    {"guardian", "guard", "garden", "gardian", "corridor"},
    {"gap", "cap", "gaps", "aperture", "doorway"},
    {"auto", "automatic", "oto"},
    {"look", "luke", "describe", "what is ahead", "what's ahead", "read"},
    {"help", "commands", "command"},
    {"quiet", "silence", "shut up", "stop talking"}
  };

  private static final Command[] BY_INDEX = {
    Command.MODE_SCENE,
    Command.MODE_GUARDIAN,
    Command.MODE_APERTURE,
    Command.MODE_AUTO,
    Command.DESCRIBE,
    Command.HELP,
    Command.QUIET
  };

  private final Context context;
  private final Listener listener;
  private SpeechRecognizer recognizer;
  private Intent intent;
  private boolean wantListening = false;
  private boolean muted = false;
  private boolean announcedListening = false;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

  // long enough for the previous session to finish tearing down, short enough to feel continuous
  private static final long RESTART_DELAY_MS = 300;

  // partial results repeat the same words as they firm up, so one command per second at most
  private static final long COMMAND_COOLDOWN_MS = 1000;

  public VoiceCommands(Context context, Listener listener) {
    this.context = context;
    this.listener = listener;
  }

  public boolean isAvailable() {
    return SpeechRecognizer.isRecognitionAvailable(context);
  }

  /** call once the RECORD_AUDIO permission is actually granted */
  public void start() {
    if (recognizer != null) {
      return;
    }
    if (!isAvailable()) {
      Log.w(TAG, "REACH_VOICE no recogniser on this device");
      return;
    }
    recognizer = SpeechRecognizer.createSpeechRecognizer(context);
    intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toLanguageTag());
    // nOT offline-only. asking for offline on a device with no downloaded model gets you a
    // recogniser that returns ERROR_NO_MATCH forever and never a single word
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
    recognizer.setRecognitionListener(new Handler());
    wantListening = true;
    listen();
  }

  /** while our own speech is playing, or the recogniser transcribes the app talking to itself */
  public void setMuted(boolean value) {
    if (muted == value) {
      return;
    }
    muted = value;
    if (muted) {
      cancel();
    } else {
      listen();
    }
  }

  public void stop() {
    wantListening = false;
    cancel();
    if (recognizer != null) {
      recognizer.destroy();
      recognizer = null;
    }
  }

  private void cancel() {
    if (recognizer != null) {
      try {
        recognizer.cancel();
      } catch (Exception e) {
        // the recogniser throws if it was never started; nothing to do about it
      }
    }
  }

  /**
   * Restarting has to go through the main thread's queue with a gap.
   *
   * <p>Calling startListening() from inside onError or onResults is rejected - the recogniser is
   * still tearing the last session down and the new one dies silently, which looks exactly like
   * voice control not existing.
   */
  private void listen() {
    if (recognizer == null || !wantListening || muted) {
      return;
    }
    main.removeCallbacks(startListening);
    main.postDelayed(startListening, RESTART_DELAY_MS);
  }

  private final Runnable startListening =
      () -> {
        if (recognizer == null || !wantListening || muted) {
          return;
        }
        try {
          recognizer.startListening(intent);
          if (!announcedListening) {
            announcedListening = true;
            Log.i(TAG, "REACH_VOICE listening");
          }
        } catch (Exception e) {
          Log.w(TAG, "REACH_VOICE could not start", e);
        }
      };

  private long lastFiredMs = 0;

  private void handle(ArrayList<String> heard) {
    if (heard == null || heard.isEmpty()) {
      return;
    }
    if (System.currentTimeMillis() - lastFiredMs < COMMAND_COOLDOWN_MS) {
      return;
    }
    for (String phrase : heard) {
      String lower = phrase.toLowerCase(Locale.UK);
      for (int i = 0; i < KEYWORDS.length; i++) {
        for (String word : KEYWORDS[i]) {
          if (lower.contains(word)) {
            Log.i(TAG, "REACH_VOICE heard \"" + phrase + "\" -> " + BY_INDEX[i]);
            lastFiredMs = System.currentTimeMillis();
            listener.onCommand(BY_INDEX[i]);
            return;
          }
        }
      }
    }
    Log.i(TAG, "REACH_VOICE heard \"" + heard.get(0) + "\" -> no match");
  }

  private final class Handler implements RecognitionListener {
    @Override
    public void onResults(Bundle results) {
      handle(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
      listen();
    }

    @Override
    public void onError(int error) {
      // nO_MATCH and SPEECH_TIMEOUT are the normal case, not a fault: they fire every time nobody
      // says anything, which is most of the time. everything else is worth seeing in a log
      if (error != SpeechRecognizer.ERROR_NO_MATCH
          && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
        Log.w(TAG, "REACH_VOICE error " + error);
      }
      listen();
    }

    @Override
    public void onPartialResults(Bundle partial) {
      // act on partials too. waiting for the final result adds most of a second to every command,
      // and a one-word command is usually already unambiguous in the partial
      handle(partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
    }

    @Override
    public void onReadyForSpeech(Bundle params) {}

    @Override
    public void onBeginningOfSpeech() {
      Log.i(TAG, "REACH_VOICE hearing something");
    }

    @Override
    public void onRmsChanged(float rms) {}

    @Override
    public void onBufferReceived(byte[] buffer) {}

    @Override
    public void onEndOfSpeech() {}

    @Override
    public void onEvent(int type, Bundle params) {}
  }
}
