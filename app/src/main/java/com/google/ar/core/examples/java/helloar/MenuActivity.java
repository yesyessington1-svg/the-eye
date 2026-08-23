/*
 * The Eye - the screen you land on.
 * Built on Google's hello_ar_java sample (Apache 2.0).
 */

package com.google.ar.core.examples.java.helloar;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * Mode picker, and the only place the app explains itself.
 *
 * <p>Sending someone straight into a camera view with four unlabelled modes means the first thing
 * they do is guess. A judge holding this for thirty seconds should be able to read what each mode
 * answers without anyone standing over them.
 *
 * <p>The wearer never sees it. They say a mode name and the AR screen switches under them, so this
 * is the sighted half of a two-audience interface.
 */
public class MenuActivity extends Activity {

  /** which mode the AR screen should start in, as a {@link ModeArbiter.Mode} name */
  public static final String EXTRA_MODE = "reach.mode";

  private ModeArbiter.Mode chosen = ModeArbiter.Mode.AUTO;
  private final View[] cards = new View[4];

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_menu);

    cards[0] = findViewById(R.id.card_guardian);
    cards[1] = findViewById(R.id.card_aperture);
    cards[2] = findViewById(R.id.card_scene);
    cards[3] = findViewById(R.id.card_auto);

    bind(cards[0], ModeArbiter.Mode.GUARDIAN, 0);
    bind(cards[1], ModeArbiter.Mode.APERTURE, 1);
    bind(cards[2], ModeArbiter.Mode.SCENE, 2);
    bind(cards[3], ModeArbiter.Mode.AUTO, 3);
    select(3);

    findViewById(R.id.start_button).setOnClickListener(v -> launch());
  }

  private void bind(View card, ModeArbiter.Mode mode, int index) {
    // one tap picks, a second one starts. saves a trip to the button at the bottom without
    // making a single stray tap launch the camera
    card.setOnClickListener(
        v -> {
          if (chosen == mode) {
            launch();
            return;
          }
          chosen = mode;
          select(index);
        });
  }

  private void select(int index) {
    // the selected row is marked by one 2dp accent bar and a brighter title. nothing else moves,
    // because a row that grows or glows on selection is decoration pretending to be feedback
    int[] bars = {R.id.bar_guardian, R.id.bar_aperture, R.id.bar_scene, R.id.bar_auto};
    int[] titles = {R.id.title_guardian, R.id.title_aperture, R.id.title_scene, R.id.title_auto};
    for (int i = 0; i < bars.length; i++) {
      View bar = findViewById(bars[i]);
      if (bar != null) {
        bar.setVisibility(i == index ? View.VISIBLE : View.INVISIBLE);
      }
      TextView title = findViewById(titles[i]);
      if (title != null) {
        title.setTextColor(i == index ? 0xFF7FE08A : 0xFFFAFAFA);
      }
    }
    TextView start = findViewById(R.id.start_button);
    if (start != null) {
      start.setText("START  " + ModeArbiter.shortName(chosen));
    }
  }

  private void launch() {
    Intent intent = new Intent(this, HelloArActivity.class);
    intent.putExtra(EXTRA_MODE, chosen.name());
    startActivity(intent);
  }
}
