/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.helloar;

import android.content.DialogInterface;
import android.content.res.Resources;
import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.KeyEvent;
import android.view.View;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.ArCoreApk.Availability;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Config.InstantPlacementMode;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.LightEstimate;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DepthSettings;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.samplerender.Framebuffer;
import com.google.ar.core.examples.java.common.samplerender.GLError;
import com.google.ar.core.examples.java.common.samplerender.Mesh;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.Shader;
import com.google.ar.core.examples.java.common.samplerender.Texture;
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
public class HelloArActivity extends AppCompatActivity implements SampleRender.Renderer {

  private static final String TAG = HelloArActivity.class.getSimpleName();

  private static final String SEARCHING_PLANE_MESSAGE = "Searching for surfaces...";
  private static final String WAITING_FOR_TAP_MESSAGE = "Tap on a surface to place an object.";

  // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
  // constants.
  private static final float[] sphericalHarmonicFactors = {
    0.282095f,
    -0.325735f,
    0.325735f,
    -0.325735f,
    0.273137f,
    -0.273137f,
    0.078848f,
    -0.273137f,
    0.136569f,
  };

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100f;

  private static final int CUBEMAP_RESOLUTION = 16;
  private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private TapHelper tapHelper;
  private SampleRender render;

  private PlaneRenderer planeRenderer;
  private BackgroundRenderer backgroundRenderer;
  private Framebuffer virtualSceneFramebuffer;
  private boolean hasSetTextureNames = false;

  private final DepthSettings depthSettings = new DepthSettings();

  private final GuardianCorridor guardian = new GuardianCorridor();
  private final MotionBudget motionBudget = new MotionBudget();
  private final TerrainWatch terrain = new TerrainWatch();
  private SceneDescriber describer;
  private SpeechManager speech;
  private DetectionOverlayView detectionOverlay;
  private PointedObject.Result pointed = null;
  private float pointedMeters = Float.NaN;
  private final float[] pointEnds = new float[2];
  private final float[] pointEndsTex = new float[2];
  private final float[] pointEndsView = new float[2];
  // the probe marches in 3D now, so there is no array of sample points to keep - just the two ends
  // of the line we draw
  private final float[] probeViewCoords = new float[4];
  private static final float[] WORLD_UP = {0f, 1f, 0f};

  // rear cameras on Android phones are almost universally mounted a quarter turn from the device's
  // upright. this is the constant the original hardcoded rotation was standing in for
  private static final int SENSOR_MOUNT_DEGREES = 90;
  private final float[] upInCamera = new float[3];
  private final float[] rayEnds = new float[4];
  private final float[] rayEndsTex = new float[4];
  private final float[] caneEnds = new float[2];
  private final float[] caneEndsView = new float[2];

  // 84% of raw pixels land in the lowest confidence bucket, so this keeps roughly the top sixth
  // sparse - MIN_HITS and the agreement window decide if what survives is enough
  private static final int RAW_MIN_CONFIDENCE = 50;

  // roughly a 10cm patch at half a metre. fewer than this is depth noise at the edge of the frame
  private static final int SELF_POINTS_SUPPRESS = 40;

  private static final long STALE_RECOVER_AFTER_MS = 2000;
  private static final long STALE_RECOVER_COOLDOWN_MS = 6000;
  private long lastRecoverMs = 0;
  private int recoverAttempts = 0;

  // fallback perception - the vision model is woken only inside blind windows, not run
  // continuously like every other system in this space. cheap sense hands off to expensive one
  private static final long FALLBACK_AFTER_MS = 1500;
  private static final long FALLBACK_COOLDOWN_MS = 10000;
  private long blindSinceMs = 0;
  private long lastFallbackMs = 0;

  // if the backup has answered this many times and depth still hasn't come back, the sensor is not
  // having a moment, it is down. Asking again every ten seconds costs money and network and tells
  // the wearer nothing new - at that point the honest thing is to say so once and stop
  private static final int FALLBACK_GIVE_UP_AFTER = 3;
  private int consecutiveFallbacks = 0;
  private boolean saidSensorDown = false;
  private volatile String fallbackAnswer = null;
  private long fallbackAnswerMs = 0;

  // how long a backup answer stands in for the depth sensor. matches the HUD's own window
  private static final long FALLBACK_FRESH_MS = 7000;

  // how long the backup channel gets before we give up and say we are blind. covers the 1.5s
  // trigger delay plus a slow round trip
  private static final long FALLBACK_GRACE_MS = 5000;
  private boolean caneShowing = false;
  private final float[] boxImageCoords = new float[64];
  private final float[] boxViewCoords = new float[64];
  private boolean overlayHadBoxes = false;
  private VisionDetector vision;
  private MonoDepth mono;

  // measured at 2.4 seconds a frame before XNNPACK, so this cannot be a continuous channel no
  // matter how fast we make it. It doesn't need to be: ARCore carries every frame it can see, and
  // the model exists for the frames it can't. We ask for one when depth has gone blind, and
  // occasionally otherwise so there is always a recent map to fall back on
  private static final long MONO_INTERVAL_MS = 3000;
  private static final long MONO_BLIND_INTERVAL_MS = 700;
  private long lastMonoOfferMs = 0;

  // a grid in the depth image's own space, and the same points carried into the camera image's
  // space by ARCore. the two pictures have different fields of view and different aspect ratios,
  // so pairing them by raw pixel index compares unrelated parts of the room
  private final float[] fitTex = new float[MonoDepth.FIT_GRID * MonoDepth.FIT_GRID * 2];
  private final float[] fitImage = new float[MonoDepth.FIT_GRID * MonoDepth.FIT_GRID * 2];
  private boolean fitGridBuilt = false;
  private final YuvToBitmap frameConverter = new YuvToBitmap();

  // hold the L this long before it fires. long enough that a hand crossing the view doesn't
  // trigger a request, short enough not to feel like a chore
  private static final long GESTURE_HOLD_MS = 650;

  // covers three consecutive landmarker misses at the 200ms detect interval
  private static final long GESTURE_GRACE_MS = 700;

  // eight requests went out in fifty seconds during testing. a description is a question the wearer
  // asked, and nobody asks the same question six times a minute
  private static final long DESCRIBE_COOLDOWN_MS = 6000;
  private long lastDescribeMs = 0;
  private static final long GESTURE_LOOK_INTERVAL_MS = 200;
  private long gestureSeenSinceMs = 0;
  private long gestureLastSeenMs = 0;
  private long lastGestureLookMs = 0;

  // set from the volume key or a screen tap, consumed on the next frame. onDrawFrame is the only
  // place a camera image can be acquired, so the trigger has to be a flag rather than a direct call
  private volatile boolean describeRequested = false;
  private boolean personOverride = false;
  // EfficientDet-Lite0 called a water bottle a person in our own logs. a real person does not
  // appear for one frame and vanish, so a box has to survive this many consecutive frames before
  // it is allowed to interrupt anyone
  private static final int PERSON_STABLE_FRAMES = 3;
  private int personFrames = 0;

  private final ModeArbiter modes = new ModeArbiter();
  private VoiceCommands voice;
  private SessionRecorder recorder;
  private ModeArbiter.Channel channel = ModeArbiter.Channel.QUIET;

  // a person who fills less of the view than this is far enough away that depth would have seen
  // them if they mattered. tuned by eye, not measured - flagged in DEMO.md
  private static final float PERSON_MIN_SHARE = 0.06f;

  private volatile String lastDescription = null;
  private long lastDescriptionMs = 0;

  private TextView hudState;
  private TextView hudDetail;
  private TextView hudChannel;
  private TextView modeButton;
  private TextView dirLeft;
  private TextView dirCentre;
  private TextView dirRight;
  private View hapticPulse;
  private int lastLitDirection = -2;
  private volatile float lastPulseAlpha = -1f;
  private String lastHudChannel = "";
  private String lastHudState = "";
  private String lastHudDetail = "";

  private static final int COLOR_CLEAR = Color.parseColor("#7BD88F");
  private static final int COLOR_HAZARD = Color.parseColor("#FF5C4D");
  private static final int COLOR_WARN = Color.parseColor("#FFC24B");

  // built, measured, switched off. left in the tree because the measurements are worth keeping
  // pROBE: point at a thing, hear what it is. needs hand pose, image direction, un-rotation and a
  // hitbox match to agree at once - often enough for a demo, not often enough for a feature
  // mONO: Depth Anything V2. the relative map is fine but the fit onto ARCore's metres never got
  // above r=0, so 98MB of model produced arbitrary numbers
  private static final boolean PROBE_ENABLED = false;
  private static final boolean MONO_ENABLED = false;

  // below this the obstacle outranks the gap: you are not routing any more, you are stopping
  private static final float IMMINENT_M = 1.00f;

  // above MotionBudget's 0.05 still threshold, so head sway on the spot is not walking
  private static final float WALKING_MPS = 0.15f;

  // log-odds the grid must hold at that distance before the loud stop is allowed. below the grid's
  // own belief threshold of 1.75, because a real thing arriving should not have to be certain
  // before it may be shouted about
  private static final float BEAM_MIN_SUPPORT = 0.8f;

  // a box whose own depth is within this of the corridor's reading is the thing the corridor hit
  private static final float HAZARD_NAME_TOLERANCE_M = 0.50f;
  // a gap this close to straight ahead is not worth a sentence - just keep walking
  private static final float GAP_SPEAK_DEG = 8.0f;

  // reused rather than allocated per frame - onDrawFrame runs 30 times a second
  private static final GuardianCorridor.Reading BLIND_ON_TRACKING_LOSS =
      new GuardianCorridor.Reading(GuardianCorridor.State.BLIND, 0f, 0f, 0);
  private HapticEngine haptics;
  // ceiling on the CPU camera image. more pixels find smaller obstacles - a bottle at 3m is 12
  // pixels wide at VGA and 36 at 1080p, against the ~25 the detector needs
  // this was capped at 1280 while the JPEG conversion still ran on the render thread, where it
  // starved ARCore and took raw depth validity from a 61-99% band to a median of zero
  private static final int CPU_IMAGE_MAX_WIDTH = 1920;

  private GuardianCorridor.Reading guardianReading = null;

  // how far ahead the floor first comes into view, metres. large means a blind zone at your feet
  private float groundSightM = Float.NaN;

  // beyond this the near floor is unseen and "clear" is not a claim we are entitled to make
  private static final float GROUND_SIGHT_LIMIT_M = 2.50f;

  // the floor running this much shorter than the free space is a drop, not a corridor
  private static final float FLOOR_RUNOUT_M = 1.00f;
  // and it takes this many floor samples before we are willing to say so
  private static final int FLOOR_RUNOUT_MIN_SAMPLES = 100;
  // and nothing within this may be standing in the way, or IT is why the floor stopped
  private static final float FLOOR_RUNOUT_CLEAR_BARRIER_M = 2.50f;

  // where the visible floor ends, metres. NaN when we cannot see any
  private float floorEndsM = Float.NaN;

  // what the detector calls the thing the corridor is currently measuring, or null
  // the detector runs at 5Hz and the corridor at 30Hz, so on most frames there is no box to match
  // even though one existed a moment ago. holding the last name means the spoken line gets to say
  // "chair" instead of "obstacle" from a detection the wearer never knew happened
  private String hazardName = null;
  private final ObjectMemory objectMemory = new ObjectMemory();

  // what the vision model called the thing the local detector had no word for
  private volatile String askedName = null;
  private long askedNameMs = 0;
  private long lastNameAskMs = 0;

  // one naming call per this long. it costs money and a round trip, and the corridor is already
  // warning about the thing either way - the noun is an upgrade, not the warning itself
  private static final long NAME_ASK_COOLDOWN_MS = 8000;
  // and only worth asking while the wearer is slow enough for the answer to still apply
  private static final float NAME_ASK_MAX_MPS = 0.9f;
  private static final long ASKED_NAME_FRESH_MS = 6000;
  private final float[] boxCentre = new float[2];
  private final float[] boxCentreTex = new float[2];

  // The Eye bring-up instrumentation. Throttled to ~1 Hz so it can stay on during testing without
  // drowning logcat or costing frames
  private long lastDepthLogMs = 0;
  private final float[] lastTranslation = new float[3];
  private boolean haveLastTranslation = false;
  private boolean[] depthSettingsMenuDialogCheckboxes = new boolean[2];

  private final InstantPlacementSettings instantPlacementSettings = new InstantPlacementSettings();
  private boolean[] instantPlacementSettingsMenuDialogCheckboxes = new boolean[1];
  // Assumed distance from the device camera to the surface on which user will try to place objects.
  // This value affects the apparent scale of objects while the tracking method of the
  // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
  // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
  // values for AR experiences where users are expected to place objects on surfaces close to the
  // camera. Use larger values for experiences where the user will likely be standing and trying to
  // place an object on the ground or floor in front of them.
  private static final float APPROXIMATE_DISTANCE_METERS = 2.0f;

  // Point Cloud
  private VertexBuffer pointCloudVertexBuffer;
  private Mesh pointCloudMesh;
  private Shader pointCloudShader;
  // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
  // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
  private long lastPointCloudTimestamp = 0;

  // Virtual object (ARCore pawn)
  private Mesh virtualObjectMesh;
  private Shader virtualObjectShader;
  private Texture virtualObjectAlbedoTexture;
  private Texture virtualObjectAlbedoInstantPlacementTexture;

  private final List<WrappedAnchor> wrappedAnchors = new ArrayList<>();

  // Environmental HDR
  private Texture dfgTexture;
  private SpecularCubemapFilter cubemapFilter;

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] modelMatrix = new float[16];
  private final float[] viewMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];
  private final float[] modelViewMatrix = new float[16]; // view x model
  private final float[] modelViewProjectionMatrix = new float[16]; // projection x view x model
  private final float[] sphericalHarmonicsCoefficients = new float[9 * 3];
  private final float[] viewInverseMatrix = new float[16];
  private final float[] worldLightDirection = {0.0f, 0.0f, 0.0f, 0.0f};
  private final float[] viewLightDirection = new float[4]; // view x world light direction

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/* context= */ this);

    // Set up touch listener.
    // no touch trigger. we had one as a fallback for a sighted helper and it turned out to be the
    // only thing actually firing description requests - holding the phone touches the screen, and
    // the logs showed eight API calls in fifty seconds with the hand model never once seeing a hand

    // Set up renderer.
    render = new SampleRender(surfaceView, this, getAssets());

    installRequested = false;

    depthSettings.onCreate(this);
    haptics = new HapticEngine(this);
    describer = new SceneDescriber(this);
    speech = new SpeechManager(this);
    vision = new VisionDetector(this);
    try {
      mono = MONO_ENABLED ? new MonoDepth(this) : null;
    } catch (Exception e) {
      // a missing or unreadable model must not take the app down - everything else still works
      Log.w(TAG, "monocular depth unavailable", e);
      mono = null;
    }
    detectionOverlay = findViewById(R.id.detection_overlay);
    hudState = findViewById(R.id.hud_state);
    hudDetail = findViewById(R.id.hud_detail);
    hudChannel = findViewById(R.id.hud_channel);
    modeButton = findViewById(R.id.mode_button);
    dirLeft = findViewById(R.id.dir_left);
    dirCentre = findViewById(R.id.dir_centre);
    dirRight = findViewById(R.id.dir_right);
    hapticPulse = findViewById(R.id.haptic_pulse);
    // the menu says which mode to open in; launched any other way we keep the arbiter's default
    String requested = getIntent() == null ? null : getIntent().getStringExtra(MenuActivity.EXTRA_MODE);
    if (requested != null) {
      try {
        modes.setMode(ModeArbiter.Mode.valueOf(requested));
      } catch (IllegalArgumentException e) {
        // an unknown name is not worth crashing over
      }
    }

    voice = new VoiceCommands(this, this::onVoiceCommand);
    recorder = new SessionRecorder(this, System.currentTimeMillis());

    modeButton.setText(ModeArbiter.shortName(modes.mode()));
    modeButton.setOnClickListener(
        v -> {
          ModeArbiter.Mode next = modes.cycle();
          modeButton.setText(ModeArbiter.shortName(next));
          // spoken as well as printed. the wearer of this device cannot read the button they just
          // pressed, and a helper standing behind them needs to hear the same thing
          speech.announce(SpeechManager.Level.REQUESTED, ModeArbiter.announce(next));
        });
    instantPlacementSettings.onCreate(this);
    ImageButton settingsButton = findViewById(R.id.settings_button);
    settingsButton.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            PopupMenu popup = new PopupMenu(HelloArActivity.this, v);
            popup.setOnMenuItemClickListener(HelloArActivity.this::settingsMenuClick);
            popup.inflate(R.menu.settings_menu);
            popup.show();
          }
        });
  }

  /** Menu button to launch feature specific settings. */
  protected boolean settingsMenuClick(MenuItem item) {
    if (item.getItemId() == R.id.depth_settings) {
      launchDepthSettingsMenuDialog();
      return true;
    } else if (item.getItemId() == R.id.instant_placement_settings) {
      launchInstantPlacementSettingsMenuDialog();
      return true;
    }
    return false;
  }

  @Override
  protected void onDestroy() {
    if (recorder != null) {
      recorder.close();
      recorder = null;
    }
    if (voice != null) {
      voice.stop();
      voice = null;
    }
    if (vision != null) {
      vision.close();
      vision = null;
    }
    if (mono != null) {
      mono.close();
      mono = null;
    }
    if (describer != null) {
      describer.close();
      describer = null;
    }
    if (speech != null) {
      speech.close();
      speech = null;
    }
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        // Always check the latest availability.
        Availability availability = ArCoreApk.getInstance().checkAvailability(this);

        // In all other cases, try to install ARCore and handle installation failures.
        if (availability != Availability.SUPPORTED_INSTALLED) {
          switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            case INSTALL_REQUESTED:
              installRequested = true;
              return;
            case INSTALLED:
              break;
          }
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
          androidx.core.app.ActivityCompat.requestPermissions(
              this, new String[] {android.Manifest.permission.RECORD_AUDIO}, 7);
        } else if (voice != null) {
          voice.start();
        }
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session.
        session = new Session(/* context= */ this);
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      chooseHighestCpuImage();
      configureSession();
      // To record a live camera session for later playback, call
      // `session.startRecording(recordingConfig)` at anytime. To playback a previously recorded AR
      // session instead of using the live camera feed, call
      // `session.setPlaybackDatasetUri(Uri)` before calling `session.resume()`. To
      // learn more about recording and playback, see:
      // https://developers.google.com/ar/develop/java/recording-and-playback
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    // a hardware key, not a point on glass. someone who can't see the screen can find a volume
    // rocker by feel; they cannot find a button drawn somewhere on a sheet of glass
    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
      describeRequested = true;
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public void onPause() {
    super.onPause();
    // a vibration that outlives the activity is the kind of thing that gets an app force-quit
    // mid-demo, and the pattern would keep firing against a stale reading
    haptics.stop();
    // the floor baseline is only meaningful for the room we were standing in
    terrain.reset();
    speech.stop();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == 7) {
      // voice is a convenience, not a dependency. refused, the mode pill still works
      if (results.length > 0
          && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
          && voice != null) {
        voice.start();
      }
      return;
    }
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(SampleRender render) {
    // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
    // an IOException.
    try {
      planeRenderer = new PlaneRenderer(render);
      backgroundRenderer = new BackgroundRenderer(render);
      virtualSceneFramebuffer = new Framebuffer(render, /* width= */ 1, /* height= */ 1);

      cubemapFilter =
          new SpecularCubemapFilter(
              render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES);
      // Load DFG lookup table for environmental lighting
      dfgTexture =
          new Texture(
              render,
              Texture.Target.TEXTURE_2D,
              Texture.WrapMode.CLAMP_TO_EDGE,
              /* useMipmaps= */ false);
      // The dfg.raw file is a raw half-float texture with two channels.
      final int dfgResolution = 64;
      final int dfgChannels = 2;
      final int halfFloatSize = 2;

      ByteBuffer buffer =
          ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize);
      try (InputStream is = getAssets().open("models/dfg.raw")) {
        is.read(buffer.array());
      }
      // SampleRender abstraction leaks here.
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.getTextureId());
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture");
      GLES30.glTexImage2D(
          GLES30.GL_TEXTURE_2D,
          /* level= */ 0,
          GLES30.GL_RG16F,
          /* width= */ dfgResolution,
          /* height= */ dfgResolution,
          /* border= */ 0,
          GLES30.GL_RG,
          GLES30.GL_HALF_FLOAT,
          buffer);
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D");

      // Point cloud
      pointCloudShader =
          Shader.createFromAssets(
                  render,
                  "shaders/point_cloud.vert",
                  "shaders/point_cloud.frag",
                  /* defines= */ null)
              .setVec4(
                  "u_Color", new float[] {31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f})
              .setFloat("u_PointSize", 5.0f);
      // four entries per vertex: X, Y, Z, confidence
      pointCloudVertexBuffer =
          new VertexBuffer(render, /* numberOfEntriesPerVertex= */ 4, /* entries= */ null);
      final VertexBuffer[] pointCloudVertexBuffers = {pointCloudVertexBuffer};
      pointCloudMesh =
          new Mesh(
              render, Mesh.PrimitiveMode.POINTS, /* indexBuffer= */ null, pointCloudVertexBuffers);

      // Virtual object to render (ARCore pawn)
      virtualObjectAlbedoTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_albedo.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.SRGB);
      virtualObjectAlbedoInstantPlacementTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_albedo_instant_placement.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.SRGB);
      Texture virtualObjectPbrTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_roughness_metallic_ao.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.LINEAR);

      virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj");
      virtualObjectShader =
          Shader.createFromAssets(
                  render,
                  "shaders/environmental_hdr.vert",
                  "shaders/environmental_hdr.frag",
                  /* defines= */ new HashMap<String, String>() {
                    {
                      put(
                          "NUMBER_OF_MIPMAP_LEVELS",
                          Integer.toString(cubemapFilter.getNumberOfMipmapLevels()));
                    }
                  })
              .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
              .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
              .setTexture("u_Cubemap", cubemapFilter.getFilteredCubemapTexture())
              .setTexture("u_DfgTexture", dfgTexture);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
    }
  }

  @Override
  public void onSurfaceChanged(SampleRender render, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    virtualSceneFramebuffer.resize(width, height);
  }

  @Override
  public void onDrawFrame(SampleRender render) {
    if (session == null) {
      return;
    }

    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(
          new int[] {backgroundRenderer.getCameraColorTexture().getTextureId()});
      hasSetTextureNames = true;
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    // Obtain the current frame from the AR Session. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    Frame frame;
    try {
      frame = session.update();
    } catch (CameraNotAvailableException e) {
      Log.e(TAG, "Camera not available during onDrawFrame", e);
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      return;
    }
    Camera camera = frame.getCamera();

    // Update BackgroundRenderer state to match the depth settings.
    try {
      backgroundRenderer.setUseDepthVisualization(
          render, depthSettings.depthColorVisualizationEnabled());
      backgroundRenderer.setUseOcclusion(render, depthSettings.useDepthForOcclusion());
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
      return;
    }
    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame);

    boolean depthArrived = false;
    boolean tracking = camera.getTrackingState() == TrackingState.TRACKING;
    if (!tracking) {
      // tracking is gone, so Guardian gets no frames and would simply go quiet. silence in this
      // system means "corridor is clear", so going quiet here would be the sensor failing open
      guardianReading = BLIND_ON_TRACKING_LOSS;
      motionBudget.updateWithoutPose();
      haptics.update(guardianReading, motionBudget.shouldAskForMotion());
    }
    if (tracking) {
      try (Image depthImage = frame.acquireDepthImage16Bits()) {
        // Guardian runs every frame no matter what's on screen. gating it on the depth-visualisation
        // toggle would mean the safety sense switches off whenever someone turns off the debug view
        // last frame's speed. motionBudget updates further down because it needs to know whether
        // we ended up blind, and one frame of lag on a walking pace is nothing
        // ARCore knows which way is down. asking it costs nothing and makes every vertical
        // measurement independent of how the phone happens to be tilted on someone's head
        camera.getPose().inverse().rotateVector(WORLD_UP, 0, upInCamera, 0);
        guardianReading =
            guardian.evaluate(
                depthImage, camera.getImageIntrinsics(), motionBudget.speedMps(), upInCamera);
        long memoryNow = System.currentTimeMillis();
        rememberObjects(frame, depthImage, memoryNow);
        objectMemory.expire(memoryNow);
        hazardName =
            guardianReading.state == GuardianCorridor.State.HAZARD
                ? objectMemory.nameAt(
                    guardianReading.distanceMeters,
                    guardianReading.lateralMeters,
                    memoryNow,
                    motionBudget.speedMps())
                : null;

        // depth is certain, the local detector has no word for it, so ask the one that has every
        // word. pillars, radiators, desks and bollards are all outside COCO's eighty classes
        if (hazardName == null
            && guardianReading.state == GuardianCorridor.State.HAZARD
            && guardianReading.distanceMeters < 2.5f
            && motionBudget.speedMps() < NAME_ASK_MAX_MPS
            && describer.isConfigured()
            && !describer.isBusy()
            && memoryNow - lastNameAskMs > NAME_ASK_COOLDOWN_MS) {
          lastNameAskMs = memoryNow;
          try (Image nameImage = frame.acquireCameraImage()) {
            Bitmap nameShot = frameConverter.convert(nameImage, 2, uprightDegrees(upInCamera));
            describer.describe(
                nameShot,
                SceneDescriber.Mode.NAME,
                "",
                text -> {
                  String word = text == null ? "" : text.trim().toLowerCase(Locale.UK);
                  if (!word.isEmpty() && !word.startsWith("unknown") && word.length() < 24) {
                    askedName = word;
                    askedNameMs = System.currentTimeMillis();
                    Log.i(TAG, "EYE_NAME model called it \"" + word + "\"");
                  }
                });
          } catch (NotYetAvailableException e) {
            // no camera image this frame; the cooldown will let us try again
          }
        }
        if (hazardName == null
            && askedName != null
            && memoryNow - askedNameMs < ASKED_NAME_FRESH_MS
            && guardianReading.state == GuardianCorridor.State.HAZARD) {
          hazardName = askedName;
        }
        // naN means no floor in view at all, which is the worst case, not the best
        float nearestFloor = guardian.nearestFloorMetres();
        groundSightM = Float.isNaN(nearestFloor) ? Float.POSITIVE_INFINITY : nearestFloor;
        floorEndsM = guardian.furthestFloorMetres();

        // the smoothed stream froze for 68 seconds in one logged walk while the raw stream's
        // confidence histogram kept changing every frame. Reconfiguring the session fired fourteen
        // times and never unstuck it. So when the smoothed image stops being new we stop reading
        // it, rather than announcing blindness next to a sensor that is still working
        // the occupancy grid runs every frame off the raw stream, whatever the smoothed one says.
        // it is the only place ARCore's per-pixel confidence is used, and confidence is what tells
        // a hallucinated surface apart from a real one at the same distance
        try (Image beamDepth = frame.acquireRawDepthImage16Bits();
            Image beamConfidence = frame.acquireRawDepthConfidenceImage()) {
          guardian.observeRaw(
              beamDepth,
              beamConfidence,
              camera.getImageIntrinsics(),
              upInCamera,
              motionBudget.movedSinceLastFrameM());
        } catch (NotYetAvailableException e) {
          // no raw frame this tick; the grid simply gets no new evidence and decays
        }

        if (guardianReading.state == GuardianCorridor.State.STALE) {
          try (Image rawDepth = frame.acquireRawDepthImage16Bits();
              Image rawConfidence = frame.acquireRawDepthConfidenceImage()) {
            GuardianCorridor.Reading fromRaw =
                guardian.evaluateRaw(
                    rawDepth,
                    rawConfidence,
                    RAW_MIN_CONFIDENCE,
                    camera.getImageIntrinsics(),
                    motionBudget.speedMps(),
                    upInCamera);
            if (fromRaw.state != GuardianCorridor.State.BLIND) {
              guardianReading = fromRaw;
            }
          } catch (NotYetAvailableException e) {
            // raw is not up yet either. the STALE reading stands, and STALE is honest
          }
        }
        depthArrived = true;
        terrain.update(
            guardian.floorSamples(), guardian.floorDistances(), guardian.floorSampleCount());

        // fit the learned map onto ARCore's metres every frame - the fit is cheap and the scene
        // changes, so a stale scale is worse than no scale
        if (mono != null) {
          if (!fitGridBuilt) {
            for (int gy = 0; gy < MonoDepth.FIT_GRID; gy++) {
              for (int gx = 0; gx < MonoDepth.FIT_GRID; gx++) {
                int i = (gy * MonoDepth.FIT_GRID + gx) * 2;
                fitTex[i] = (gx + 0.5f) / MonoDepth.FIT_GRID;
                fitTex[i + 1] = (gy + 0.5f) / MonoDepth.FIT_GRID;
              }
            }
            fitGridBuilt = true;
          }
          frame.transformCoordinates2d(
              Coordinates2d.TEXTURE_NORMALIZED, fitTex, Coordinates2d.IMAGE_NORMALIZED, fitImage);
          mono.fitTo(depthImage, fitTex, fitImage, MonoDepth.FIT_GRID * MonoDepth.FIT_GRID);
          long monoNow = System.currentTimeMillis();
          boolean depthBlind =
              guardianReading != null
                  && (guardianReading.state == GuardianCorridor.State.BLIND
                      || guardianReading.state == GuardianCorridor.State.STALE);
          long wanted = depthBlind ? MONO_BLIND_INTERVAL_MS : MONO_INTERVAL_MS;
          if (monoNow - lastMonoOfferMs > wanted && !mono.isBusy()) {
            lastMonoOfferMs = monoNow;
            try (Image monoImage = frame.acquireCameraImage()) {
              mono.offer(frameConverter.convert(monoImage, 1, uprightDegrees(upInCamera)));
            } catch (NotYetAvailableException e) {
              // next window
            }
          }
        }

        // the virtual cane. the ray comes from the last hand detection, which is a frame or two old
        // - irrelevant next to how long a person holds a pointing gesture
        pointed = null;
        pointedMeters = Float.NaN;
        if (PROBE_ENABLED && vision.isPointing()) {
          float[] tip = vision.fingertipImage();
          float[] dir = vision.pointingImageDir();
          pointed =
              PointedObject.find(
                  tip[0], tip[1], dir[0], dir[1],
                  vision.objectBoxes(), vision.objectLabels(), vision.objectCount());

          if (pointed.found) {
            // one depth sample, at the middle of the box. the previous design needed depth to be
            // right along a whole path; this needs it right in one place
            pointEnds[0] = pointed.centreU;
            pointEnds[1] = pointed.centreV;
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_NORMALIZED, pointEnds, Coordinates2d.TEXTURE_NORMALIZED,
                pointEndsTex);
            pointedMeters = sampleDepth(depthImage, pointEndsTex[0], pointEndsTex[1]);
            if (Float.isNaN(pointedMeters) && mono != null) {
              // ARCore couldn't see it; the learned map might have
              pointedMeters = mono.metresAt(pointed.centreU, pointed.centreV);
            }

            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_NORMALIZED, pointEnds, Coordinates2d.VIEW, pointEndsView);
            rayEnds[0] = tip[0];
            rayEnds[1] = tip[1];
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_NORMALIZED, rayEnds, Coordinates2d.VIEW, probeViewCoords);
            detectionOverlay.setCane(
                probeViewCoords[0],
                probeViewCoords[1],
                pointEndsView[0],
                pointEndsView[1],
                true,
                Float.isNaN(pointedMeters)
                    ? pointed.label.toUpperCase(Locale.UK)
                    : String.format(
                        Locale.UK, "%s  %.1f m", pointed.label.toUpperCase(Locale.UK),
                        pointedMeters));
            caneShowing = true;
          }
        } else if (caneShowing) {
          detectionOverlay.clearCane();
          caneShowing = false;
        }
        logDepthStats(frame, camera, depthImage);
        if (depthSettings.useDepthForOcclusion()
            || depthSettings.depthColorVisualizationEnabled()) {
          backgroundRenderer.updateCameraDepthTexture(depthImage);
        }
      } catch (NotYetAvailableException e) {
        // normal for a frame or two, but a cold start gave us eleven straight seconds of this, and
        // that is exactly when telling the user to move is worth more than staying quiet
      }

      // the user asked to be told what's in front of them. we take the whole frame rather than a
      // framed region: someone who can't see the frame can't aim it, and a padded crop of the
      // middle was answering "I can't make that out" to a door, a TV and a person alike
      // look for the shutter gesture and for people a few times a second. the conversion is not cheap and it runs
      // on the GL thread, because a camera image has to be consumed before it's closed
      long gestureNow = System.currentTimeMillis();
      if (gestureNow - lastGestureLookMs > GESTURE_LOOK_INTERVAL_MS) {
        lastGestureLookMs = gestureNow;
        try (Image cameraImage = frame.acquireCameraImage()) {
          vision.offer(cameraImage, uprightDegrees(upInCamera));
        } catch (NotYetAvailableException e) {
          // the camera image lags the pose now and then, nothing to do
        }
      }
      // hold the L for GESTURE_HOLD_MS
      // a gap only breaks the hold once it outlasts the detector's own dropouts - zeroing on the
      // first missed frame restarted the timer every second and the shutter never fired
      if (vision.isGestureVisible()) {
        gestureLastSeenMs = gestureNow;
        if (gestureSeenSinceMs == 0) {
          gestureSeenSinceMs = gestureNow;
        } else if (gestureNow - gestureSeenSinceMs > GESTURE_HOLD_MS) {
          gestureSeenSinceMs = 0;
          vision.consumeGesture();
          describeRequested = true;
        }
      } else if (gestureSeenSinceMs != 0 && gestureNow - gestureLastSeenMs > GESTURE_GRACE_MS) {
        gestureSeenSinceMs = 0;
      }

      // a plain guard, not an early return - bailing out of onDrawFrame here would skip drawing the
      // camera background and the screen would flicker black every time the cooldown bit
      boolean cooledDown = System.currentTimeMillis() - lastDescribeMs >= DESCRIBE_COOLDOWN_MS;
      if (describeRequested && !describer.isBusy() && cooledDown) {
        describeRequested = false;
        lastDescribeMs = System.currentTimeMillis();
        try (Image cameraImage = frame.acquireCameraImage()) {
          // upright, like the detectors get - a sideways photo is harder to describe
          Bitmap shot = frameConverter.convert(cameraImage, 1, uprightDegrees(upInCamera));
          // the verdict is decided here, from the depth reading, and not by the model. a vision
          // model cannot measure distance from one photograph - asked to, it answered GO with an
          // obstacle under a metre away. it names the thing, the sensor says whether you can go
          int depthSeverity = depthSeverity(guardianReading, groundSightM > GROUND_SIGHT_LIMIT_M);
          // the model names the thing and is forbidden from guessing distances, so the sensor
          // supplies the number and the fan supplies the way round
          final String shotDistance = shotDetail(guardianReading, guardian.gap());
          describer.describe(
              shot,
              SceneDescriber.Mode.SCENE,
              depthNote(guardianReading),
              text -> {
                String spoken = combineVerdicts(depthSeverity, text);
                if (shotDistance != null) {
                  spoken = spoken + " " + shotDistance;
                }
                lastDescription = spoken;
                lastDescriptionMs = System.currentTimeMillis();
                speech.announce(SpeechManager.Level.REQUESTED, spoken);
              });
        } catch (NotYetAvailableException e) {
          lastDescription = "Camera not ready";
          lastDescriptionMs = System.currentTimeMillis();
        }
      }

      float[] translation = new float[3];
      camera.getPose().getTranslation(translation, 0);
      boolean losingPicture =
          !depthArrived
              || guardianReading == null
              || guardianReading.state == GuardianCorridor.State.STALE
              || guardianReading.state == GuardianCorridor.State.BLIND;
      motionBudget.update(translation, losingPicture);

      // how long we have been unable to see. BLIND and STALE both count - to the wearer they mean
      // the same thing, and it is the duration that decides whether it is worth waking the model
      boolean cannotSee =
          guardianReading != null
              && (guardianReading.state == GuardianCorridor.State.BLIND
                  || guardianReading.state == GuardianCorridor.State.STALE);
      long nowBlind = System.currentTimeMillis();
      if (cannotSee) {
        if (blindSinceMs == 0) {
          blindSinceMs = nowBlind;
        } else if (nowBlind - blindSinceMs > FALLBACK_AFTER_MS
            && nowBlind - lastFallbackMs > FALLBACK_COOLDOWN_MS
            && consecutiveFallbacks < FALLBACK_GIVE_UP_AFTER
            && !describer.isBusy()
            && describer.isConfigured()) {
          lastFallbackMs = nowBlind;
          consecutiveFallbacks++;
          try (Image cameraImage = frame.acquireCameraImage()) {
            Bitmap shot = frameConverter.convert(cameraImage, 1, uprightDegrees(upInCamera));
            Log.i(TAG, "depth blind for " + (nowBlind - blindSinceMs) + "ms, asking backup vision");
            describer.describe(
                shot,
                SceneDescriber.Mode.FALLBACK,
                text -> {
                  fallbackAnswer = text;
                  fallbackAnswerMs = System.currentTimeMillis();
                  // spoken at the level of a real hazard, because that is what it may be reporting
                  speech.announce(SpeechManager.Level.HIGH, spokenFallback(text));
                });
          } catch (NotYetAvailableException e) {
            // no camera image this frame either; try again on the next window
          }
        }
        if (consecutiveFallbacks >= FALLBACK_GIVE_UP_AFTER && !saidSensorDown) {
          saidSensorDown = true;
          speech.announce(
              SpeechManager.Level.CRITICAL, "Depth sensor is down. Use your cane and restart me.");
        }
      } else {
        // depth came back, so the next outage starts from a clean slate
        blindSinceMs = 0;
        consecutiveFallbacks = 0;
        saidSensorDown = false;
      }

      if (guardian.depthAgeMs() > STALE_RECOVER_AFTER_MS
          && System.currentTimeMillis() - lastRecoverMs > STALE_RECOVER_COOLDOWN_MS) {
        lastRecoverMs = System.currentTimeMillis();
        recoverAttempts++;
        // escalate: the depth-mode toggle is free but logged 14 misses in one stall, so a third
        // failure earns the session restart. blanks the preview for ~300ms, beats a dead sensor
        if (recoverAttempts <= 2) {
          Log.w(TAG, "depth frozen " + guardian.depthAgeMs() + "ms, toggling depth mode");
          recoverDepth();
        } else {
          Log.w(TAG, "depth frozen " + guardian.depthAgeMs() + "ms, restarting session");
          restartSession();
        }
      } else if (guardian.depthAgeMs() < STALE_RECOVER_AFTER_MS) {
        recoverAttempts = 0;
      }

      // for the case where depth says clear and a person is standing in it: a hand at 45cm was
      // invisible and smooth depth painted the wall straight through it
      // when depth says the wearer's own body is in shot, every person box is suspect - it is the
      // wearer. the screen-space version of this test fired 0 times in 119 frames while the
      // overlay drew "person" around a sock
      boolean bodyInShot = guardian.selfPoints() >= SELF_POINTS_SUPPRESS;
      boolean personNow =
          !vision.isHandVisible()
              && !bodyInShot
              && !vision.personLooksLikeOwnFeet()
              && vision.isPersonVisible()
              && vision.personFrameShare() > PERSON_MIN_SHARE
              && (guardianReading == null || guardianReading.state == GuardianCorridor.State.CLEAR);
      personFrames = personNow ? personFrames + 1 : 0;
      personOverride = personFrames >= PERSON_STABLE_FRAMES;
      // one motor, so exactly one thing gets said. ordered by what hurts most if missed: the floor
      // disappearing beats a collision, a collision beats a person we can only guess the range of,
      // and every warning beats an admission that we can't see
      // every detection gets drawn, not just people, and the one being pointed at is drawn thick.
      // without seeing all of them there is no way to tell "you aimed badly" from "the detector
      // never saw that chair", and we spent a long time guessing which was which
      int boxes = Math.min(vision.objectCount(), boxImageCoords.length / 4);
      if (boxes > 0) {
        System.arraycopy(vision.objectBoxes(), 0, boxImageCoords, 0, boxes * 4);
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_NORMALIZED, boxImageCoords, Coordinates2d.VIEW, boxViewCoords);
        detectionOverlay.setBoxes(
            boxViewCoords,
            vision.objectLabels(),
            boxes,
            pointed != null && pointed.found ? pointed.index : -1);
        overlayHadBoxes = true;
      } else if (overlayHadBoxes) {
        detectionOverlay.setBoxes(boxViewCoords, null, 0, -1);
        overlayHadBoxes = false;
      }

      // one wearer, one skin. decide who is allowed to use it before anyone does
      channel =
          modes.decide(
              guardianReading, guardian.gap(), terrain.state(), guardian.floorSampleCount());

      announceSituation();
      if (channel == ModeArbiter.Channel.QUIET) {
        // scene mode. the world stops interrupting so the wearer can actually hear an answer -
        // this was the "the screenshots are blocked by constant vibration" failure
        haptics.stop();
      } else if (pointed != null && pointed.found) {
        // while the wearer is actively asking, the answer owns their skin. two rhythms at once is
        // one unreadable buzz. with no distance we still tap, just at a fixed rate - "there is a
        // thing there" is the useful half
        haptics.updateProbe(Float.isNaN(pointedMeters) ? 2.0f : pointedMeters);
      } else {
        // the chain below used to test every sense in turn and whichever fired first won. that is
        // how a dresser produced a drop, a gap and a step-up in the same second. now the arbiter
        // has already picked, and this only renders that decision
        if (channel == ModeArbiter.Channel.TERRAIN) {
          haptics.updateTerrain(terrain.state() == TerrainWatch.State.DROP);
        } else if (channel == ModeArbiter.Channel.APERTURE) {
          ApertureScan.Gap g = guardian.gap();
          haptics.updateGap(
              g.fit == ApertureScan.Fit.BLOCKED, g.fit == ApertureScan.Fit.SQUEEZE);
        } else if (personOverride
            && (guardianReading == null
                || guardianReading.state != GuardianCorridor.State.HAZARD)) {
          haptics.updatePerson();
        } else if (guardianReading != null) {
          haptics.update(guardianReading, motionBudget.shouldAskForMotion());
        }
      }
    }

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

    if (fallbackAnswer != null && System.currentTimeMillis() - fallbackAnswerMs < 7000) {
      updateHud(fallbackAnswer, "BACKUP VISION \u00b7 depth is blind", COLOR_HAZARD);
    } else if (gestureSeenSinceMs != 0 && !describer.isBusy()) {
      // feedback while the gesture is being held, so it's obvious the hand was seen at all
      updateHud("HOLD\u2026", "L gesture seen, keep it up", COLOR_WARN);
    } else if (vision != null && vision.isHandVisible() && !describer.isBusy() && pointed == null) {
      updateHud("HAND SEEN", "make an L, or point with one finger", COLOR_WARN);
    } else if (describer.isBusy()) {
      // silence between the button press and the answer reads as a dead app, and this one takes a
      // second or two over the network
      updateHud("ASKING\u2026", "describing what's ahead", COLOR_WARN);
    } else if (lastDescription != null && System.currentTimeMillis() - lastDescriptionMs < 6000) {
      // the spoken answer is the real output; this is here so the mirrored screen shows what the
      // user just heard
      updateHud(lastDescription, "DESCRIPTION", COLOR_CLEAR);
    } else if (motionBudget.shouldAskForMotion()) {
      // outranks everything else on screen: it's the only state where the user can fix the problem
      updateHud("SWAY", "depth needs motion, move your head slowly", COLOR_WARN);
    } else if (camera.getTrackingState() == TrackingState.PAUSED) {
      updateHud(
          "NO TRACKING", TrackingStateHelper.getTrackingFailureReasonString(camera), COLOR_WARN);
    } else if (pointed != null && pointed.found) {
      updateHud(
          Float.isNaN(pointedMeters)
              ? pointed.label.toUpperCase(Locale.UK)
              : String.format(
                  Locale.UK, "%s  %.1f m", pointed.label.toUpperCase(Locale.UK), pointedMeters),
          pointed.usedCentre ? "POINTING \u00b7 straight ahead" : "POINTING \u00b7 what you're aiming at",
          COLOR_CLEAR);
    } else if (channel == ModeArbiter.Channel.TERRAIN) {
      boolean drop = terrain.state() == TerrainWatch.State.DROP;
      updateHud(
          String.format(
              Locale.US, "%s %.0f cm", drop ? "DROP" : "STEP UP",
              Math.abs(terrain.deviationMeters()) * 100),
          drop ? "ground falls away about two paces ahead" : "ground rises about two paces ahead",
          drop ? COLOR_HAZARD : COLOR_WARN);
    } else if (guardianReading != null) {
      // the HUD follows the channel, not the raw sensor state. The previous version keyed off
      // Guardian's own state and then printed the aperture's answer underneath it, so GUARDIAN mode
      // pointed at a bottle displayed "NO READ" - the fan's verdict, in the corridor's mode. Anyone
      // watching concluded, correctly, that the screen was lying about which part of the system was
      // talking
      ApertureScan.Gap hudGap = guardian.gap();
      String raw = guardian.usingRaw() ? " \u00b7 RAW DEPTH" : "";

      // one row, three segments, whichever sense currently owns the output
      if (guardianReading.state == GuardianCorridor.State.HAZARD) {
        updateDirection(directionOf(guardianReading.lateralMeters), COLOR_HAZARD);
      } else if (channel == ModeArbiter.Channel.APERTURE
          && (hudGap.fit == ApertureScan.Fit.WALK || hudGap.fit == ApertureScan.Fit.SQUEEZE)) {
        int lit = Math.abs(hudGap.bearingDeg) <= 6f ? 0 : (hudGap.bearingDeg < 0 ? -1 : 1);
        updateDirection(lit, hudGap.fit == ApertureScan.Fit.WALK ? COLOR_CLEAR : COLOR_WARN);
      } else {
        updateDirection(-2, COLOR_CLEAR);
      }
      if (guardianReading.state == GuardianCorridor.State.BLIND
          || guardianReading.state == GuardianCorridor.State.STALE) {
        updateHud(
            guardianReading.state == GuardianCorridor.State.STALE ? "FROZEN" : "BLIND",
            blindnessDetail(guardian.blindness()),
            COLOR_WARN);
      } else if (guardianReading.state == GuardianCorridor.State.HAZARD
          && guardianReading.distanceMeters < IMMINENT_M) {
        updateHud(
            String.format(Locale.US, "STOP \u00b7 %.2f m", guardianReading.distanceMeters),
            "inside the corridor \u00b7 " + guardianReading.hitCount + " depth samples" + raw,
            COLOR_HAZARD);
      } else if (personOverride) {
        updateHud("PERSON AHEAD", "seen in colour, depth cannot measure them", COLOR_HAZARD);
      } else if (channel == ModeArbiter.Channel.APERTURE) {
        switch (hudGap.fit) {
          case WALK:
            if (hudGap.widthM <= 0f) {
              updateHud(
                  "CLEAR",
                  String.format(
                      Locale.US, "nearest barrier beyond %.1f m \u00b7 %.0f%% of fan read",
                      hudGap.barrierM, hudGap.coverage * 100),
                  COLOR_CLEAR);
              break;
            }
            updateHud(
                String.format(
                    Locale.US, "GAP %.0f cm \u00b7 %+.0f\u00b0", hudGap.widthM * 100,
                    hudGap.bearingDeg),
                String.format(
                    Locale.US, "fits (%.2f\u00d7 shoulders) \u00b7 barrier %.1f m",
                    hudGap.widthM / 0.45f, hudGap.barrierM),
                COLOR_CLEAR);
            break;
          case SQUEEZE:
            updateHud(
                String.format(
                    Locale.US, "SQUEEZE %.0f cm \u00b7 %+.0f\u00b0", hudGap.widthM * 100,
                    hudGap.bearingDeg),
                "turn your shoulders",
                COLOR_WARN);
            break;
          case BLOCKED:
            updateHud("NO GAP", "nothing in view is a body wide", COLOR_HAZARD);
            break;
          default:
            updateHud(
                "NO READ",
                String.format(Locale.US, "only %.0f%% of the fan has depth", hudGap.coverage * 100),
                COLOR_WARN);
            break;
        }
      } else if (guardianReading.state == GuardianCorridor.State.HAZARD) {
        String named = hazardName;
        updateHud(
            named == null
                ? String.format(Locale.US, "%.2f m", guardianReading.distanceMeters)
                : String.format(
                    Locale.US, "%s \u00b7 %.2f m", named.toUpperCase(Locale.US),
                    guardianReading.distanceMeters),
            "obstacle in the corridor \u00b7 " + guardianReading.hitCount + " samples" + raw,
            COLOR_HAZARD);
      } else if (objectMemory.contact(System.currentTimeMillis()) != null) {
        String touch = objectMemory.contact(System.currentTimeMillis());
        updateHud(
            touch.toUpperCase(Locale.US) + " \u00b7 CONTACT",
            "fills the frame \u00b7 too close for depth to measure",
            COLOR_HAZARD);
      } else if (floorRunsOut()) {
        String blocking =
            objectMemory.nameAt(floorEndsM, 0f, System.currentTimeMillis(), motionBudget.speedMps());
        updateHud(
            blocking == null ? "GROUND STOPS" : blocking.toUpperCase(Locale.US),
            String.format(
                Locale.US,
                "floor ends at %.1f m, space runs to %.1f m \u00b7 %s",
                floorEndsM,
                guardian.gap().clearAheadM,
                blocking == null ? "step, drop, or something depth cannot see"
                    : "seen in colour, invisible to depth"),
            COLOR_HAZARD);
      } else if (groundSightM > GROUND_SIGHT_LIMIT_M) {
        updateHud(
            "TILT DOWN",
            Float.isInfinite(groundSightM)
                ? "no floor in view at all \u00b7 blind below knee height"
                : String.format(
                    Locale.US, "floor only from %.1f m \u00b7 blind below knee height",
                    groundSightM),
            COLOR_WARN);
      } else {
        updateHud(
            "CLEAR",
            Float.isInfinite(groundSightM)
                ? "corridor 0.60 \u2013 2.00 m \u00b7 no floor in view" + raw
                : String.format(
                    Locale.US, "corridor 0.60 \u2013 2.00 m \u00b7 floor from %.1f m%s",
                    groundSightM, raw),
            COLOR_CLEAR);
      }
    }

    if (recorder != null && guardianReading != null) {
      ApertureScan.Gap logGap = guardian.gap();
      recorder.row(
          ModeArbiter.shortName(modes.mode()),
          channel.name(),
          guardianReading.state.name(),
          guardianReading.distanceMeters,
          guardianReading.lateralMeters,
          hazardName,
          logGap.fit.name(),
          logGap.bearingDeg,
          logGap.widthM,
          logGap.coverage,
          guardian.corridorBottom(),
          groundSightM,
          guardian.selfPoints(),
          motionBudget.speedMps());
    }

    // -- Draw background

    if (frame.getTimestamp() != 0) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render);
    }

    // If not tracking, don't draw 3D objects.
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      return;
    }

    // the sample drew placed pawns, detected planes and the feature point cloud here. The Eye draws
    // none of it: planes and pawns are things we never use, and the point cloud made the mirrored
    // demo screen look busy in a way that hid the one number that matters. camera image plus HUD
  }


  /**
   * Shows a pop-up dialog on the first call, determining whether the user wants to enable
   * depth-based occlusion. The result of this dialog can be retrieved with useDepthForOcclusion().
   */
  private void showOcclusionDialogIfNeeded() {
    boolean isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
    if (!depthSettings.shouldShowDepthEnableDialog() || !isDepthSupported) {
      return; // Don't need to show dialog.
    }

    // Asks the user whether they want to use depth-based occlusion.
    new AlertDialog.Builder(this)
        .setTitle(R.string.options_title_with_depth)
        .setMessage(R.string.depth_use_explanation)
        .setPositiveButton(
            R.string.button_text_enable_depth,
            (DialogInterface dialog, int which) -> {
              depthSettings.setUseDepthForOcclusion(true);
            })
        .setNegativeButton(
            R.string.button_text_disable_depth,
            (DialogInterface dialog, int which) -> {
              depthSettings.setUseDepthForOcclusion(false);
            })
        .show();
  }

  private void launchInstantPlacementSettingsMenuDialog() {
    resetSettingsMenuDialogCheckboxes();
    Resources resources = getResources();
    new AlertDialog.Builder(this)
        .setTitle(R.string.options_title_instant_placement)
        .setMultiChoiceItems(
            resources.getStringArray(R.array.instant_placement_options_array),
            instantPlacementSettingsMenuDialogCheckboxes,
            (DialogInterface dialog, int which, boolean isChecked) ->
                instantPlacementSettingsMenuDialogCheckboxes[which] = isChecked)
        .setPositiveButton(
            R.string.done,
            (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
        .setNegativeButton(
            android.R.string.cancel,
            (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
        .show();
  }

  /** Shows checkboxes to the user to facilitate toggling of depth-based effects. */
  private void launchDepthSettingsMenuDialog() {
    // Retrieves the current settings to show in the checkboxes.
    resetSettingsMenuDialogCheckboxes();

    // Shows the dialog to the user.
    Resources resources = getResources();
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      // With depth support, the user can select visualization options.
      new AlertDialog.Builder(this)
          .setTitle(R.string.options_title_with_depth)
          .setMultiChoiceItems(
              resources.getStringArray(R.array.depth_options_array),
              depthSettingsMenuDialogCheckboxes,
              (DialogInterface dialog, int which, boolean isChecked) ->
                  depthSettingsMenuDialogCheckboxes[which] = isChecked)
          .setPositiveButton(
              R.string.done,
              (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
          .setNegativeButton(
              android.R.string.cancel,
              (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
          .show();
    } else {
      // Without depth support, no settings are available.
      new AlertDialog.Builder(this)
          .setTitle(R.string.options_title_without_depth)
          .setPositiveButton(
              R.string.done,
              (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
          .show();
    }
  }

  private void applySettingsMenuDialogCheckboxes() {
    depthSettings.setUseDepthForOcclusion(depthSettingsMenuDialogCheckboxes[0]);
    depthSettings.setDepthColorVisualizationEnabled(depthSettingsMenuDialogCheckboxes[1]);
    instantPlacementSettings.setInstantPlacementEnabled(
        instantPlacementSettingsMenuDialogCheckboxes[0]);
    configureSession();
  }

  private void resetSettingsMenuDialogCheckboxes() {
    depthSettingsMenuDialogCheckboxes[0] = depthSettings.useDepthForOcclusion();
    depthSettingsMenuDialogCheckboxes[1] = depthSettings.depthColorVisualizationEnabled();
    instantPlacementSettingsMenuDialogCheckboxes[0] =
        instantPlacementSettings.isInstantPlacementEnabled();
  }

  /**
   * Bring-up diagnostics, not product code. The S25 Ultra has no ToF, so this is depth-from-motion
   * and Google's docs say it needs the device to move - which the design assumes it does not.
   *
   * <p>Per second: depth image size, percentage of pixels carrying any estimate, and the same
   * restricted to a centre box. A frame can look healthy overall and be blind straight ahead.
   */
  private void logDepthStats(Frame frame, Camera camera, Image depthImage) {
    long now = System.currentTimeMillis();
    if (now - lastDepthLogMs < 1000) {
      return;
    }
    lastDepthLogMs = now;

    int width = depthImage.getWidth();
    int height = depthImage.getHeight();
    // Depth images are tightly packed, so plain indexing is safe -- no row/pixel stride needed
    ShortBuffer depthBuffer =
        depthImage.getPlanes()[0].getBuffer().order(ByteOrder.nativeOrder()).asShortBuffer();

    // centre box = middle quarter of the frame in each axis. Rough stand-in for "in front of the
    // head" until the real Guardian corridor exists
    int boxLeft = width * 3 / 8;
    int boxRight = width * 5 / 8;
    int boxTop = height * 3 / 8;
    int boxBottom = height * 5 / 8;

    int sampled = 0;
    int valid = 0;
    int boxSampled = 0;
    int boxValid = 0;
    int boxNearestMm = Integer.MAX_VALUE;
    int frameNearestMm = Integer.MAX_VALUE;
    int[] boxDepthsMm = new int[(boxRight - boxLeft) * (boxBottom - boxTop)];
    // a single aggregate cannot tell us "nothing is close" from "a few pixels see something
    // close". The hand tests showed min stuck near the background distance while a palm sat at
    // 45cm; bucketing the centre box shows whether ANY pixel registered it, which is what pins
    // down where the near dead zone actually starts
    int[] rangeBuckets = new int[5];

    final int step = 2;
    for (int y = 0; y < height; y += step) {
      for (int x = 0; x < width; x += step) {
        // mask to 16 bits: the buffer hands back signed shorts, so anything beyond 32.7 m would
        // arrive negative and quietly corrupt both the counts and the nearest-hit search
        int millimeters = depthBuffer.get(y * width + x) & 0xFFFF;
        boolean inBox = x >= boxLeft && x < boxRight && y >= boxTop && y < boxBottom;

        sampled++;
        if (inBox) {
          boxSampled++;
        }
        // 0 means "no estimate at this pixel", not "zero metres away". Treating it as a distance
        // would hand the Guardian a permanent phantom obstacle at arm's length
        if (millimeters == 0) {
          continue;
        }
        valid++;
        if (millimeters < frameNearestMm) {
          frameNearestMm = millimeters;
        }
        if (inBox) {
          if (millimeters < 400) {
            rangeBuckets[0]++;
          } else if (millimeters < 700) {
            rangeBuckets[1]++;
          } else if (millimeters < 1200) {
            rangeBuckets[2]++;
          } else if (millimeters < 2500) {
            rangeBuckets[3]++;
          } else {
            rangeBuckets[4]++;
          }
          boxDepthsMm[boxValid] = millimeters;
          boxValid++;
          if (millimeters < boxNearestMm) {
            boxNearestMm = millimeters;
          }
        }
      }
    }

    // how far the phone actually travelled since the previous log line. Test 1 (stand still) and
    // test 2 (walk) are only meaningful if we can prove which one happened -- our first run of
    // test 1 was polluted by the user tapping the screen, which moved the device. Diagnostics only:
    // this reads an ARCore world pose, which the product never touches
    float[] translation = new float[3];
    camera.getPose().getTranslation(translation, 0);
    String motion = "n/a";
    if (haveLastTranslation) {
      float dx = translation[0] - lastTranslation[0];
      float dy = translation[1] - lastTranslation[1];
      float dz = translation[2] - lastTranslation[2];
      motion =
          String.format(Locale.US, "%.1fcm", Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0);
    }
    System.arraycopy(translation, 0, lastTranslation, 0, 3);
    haveLastTranslation = true;

    // the single nearest pixel is a terrible trigger for a safety alert: one noisy pixel out of
    // ~50k fires the Guardian. Report the 5th percentile alongside it -- if the two disagree
    // badly, the minimum was an outlier and the Guardian will need the percentile instead
    String p05 = "none";
    if (boxValid > 0) {
      int[] sortedDepths = Arrays.copyOf(boxDepthsMm, boxValid);
      Arrays.sort(sortedDepths);
      p05 = String.format(Locale.US, "%.2fm", sortedDepths[boxValid / 20] / 1000f);
    }

    Log.i(
        TAG,
        String.format(
            Locale.US,
            "EYE_DEPTH %s%dx%d | centre min=%s p05=%s | frame min=%s"
                + " | centre dist[<0.4|0.4-0.7|0.7-1.2|1.2-2.5|>2.5]=%d/%d/%d/%d/%d%%"
                + " | moved=%s | %s",
            guardianReading == null
                ? "[?] "
                : "["
                    + guardianReading.state
                    + (guardian.usingRaw() ? "/RAW" : "")
                    + (guardian.selfPoints() > 0 ? "/self" + guardian.selfPoints() : "")
                    + (guardian.blindness() == GuardianCorridor.Blindness.NONE
                        ? ""
                        : ":" + guardian.blindness())
                    + (motionBudget.shouldAskForMotion() ? "/SWAY] " : "] "),
            width,
            height,
            boxNearestMm == Integer.MAX_VALUE
                ? "none"
                : String.format(Locale.US, "%.2fm", boxNearestMm / 1000f),
            p05,
            frameNearestMm == Integer.MAX_VALUE
                ? "none"
                : String.format(Locale.US, "%.2fm", frameNearestMm / 1000f),
            100 * rangeBuckets[0] / Math.max(boxValid, 1),
            100 * rangeBuckets[1] / Math.max(boxValid, 1),
            100 * rangeBuckets[2] / Math.max(boxValid, 1),
            100 * rangeBuckets[3] / Math.max(boxValid, 1),
            100 * rangeBuckets[4] / Math.max(boxValid, 1),
            motion,
            rawDepthSummary(frame)));

    // the aperture fan, dumped as a bar per bearing bin. every threshold in ApertureScan gets
    // decided from these lines, the same way every Guardian constant was - measure, don't tune
    ApertureScan.Gap g = guardian.gap();
    float[] prof = guardian.gapProfile();
    StringBuilder fan = new StringBuilder();
    for (int i = 0; i < prof.length; i++) {
      float d = prof[i];
      fan.append(d == Float.MAX_VALUE ? '#' : (char) ('0' + Math.min(9, (int) (d * 2f))));
    }
    Log.i(
        TAG,
        String.format(
            Locale.US,
            "EYE_GAP %s w=%.2fm bear=%+.1f ahead=%.2fm cov=%.0f%% edge=%b floor=%.2fm | fan[%s]",
            g.fit,
            g.widthM,
            g.bearingDeg,
            g.clearAheadM,
            g.coverage * 100f,
            g.openEdge,
            guardian.corridorBottom(),
            fan));

    Log.i(
        TAG,
        String.format(
            Locale.US,
            "EYE_VISION %s | terrain=%s dev=%.0fcm near=%d far=%d | point=%s"
                + " | depthAge=%dms ts=%d | %s"
                + " | objects=%d [%s] rot=%d | named=%s sight=%.1fm floorEnds=%.1fm",
            vision == null ? "n/a" : vision.diagnostics(),
            terrain.state(),
            terrain.deviationMeters() * 100,
            terrain.nearBandCount(),
            terrain.farBandCount(),
            pointed == null
                ? "none"
                : (pointed.found
                    ? pointed.label + (pointed.usedCentre ? "(centre)" : "") + "@" + pointedMeters
                    : "no-box"),
            guardian.depthAgeMs(),
            guardian.depthTimestamp(),
            mono == null ? "mono: off" : mono.diagnostics(),
            vision == null ? 0 : vision.objectCount(),
            vision == null ? "-" : vision.objectNames(),
            uprightDegrees(upInCamera),
            String.valueOf(hazardName) + "/" + objectMemory.diagnostics(),
            groundSightM,
            floorEndsM));
  }

  /**
   * Smooth depth reports 100% valid pixels because it interpolates every hole, so it cannot say
   * where it is guessing. Raw leaves the holes and ships a confidence plane - the only source that
   * supports saying "I cannot see here".
   */
  private String rawDepthSummary(Frame frame) {
    try (Image rawDepth = frame.acquireRawDepthImage16Bits();
        Image rawConfidence = frame.acquireRawDepthConfidenceImage()) {
      int width = rawDepth.getWidth();
      int height = rawDepth.getHeight();
      ShortBuffer depthBuffer =
          rawDepth.getPlanes()[0].getBuffer().order(ByteOrder.nativeOrder()).asShortBuffer();
      Image.Plane confidencePlane = rawConfidence.getPlanes()[0];
      ByteBuffer confidenceBuffer = confidencePlane.getBuffer().order(ByteOrder.nativeOrder());

      int sampled = 0;
      int valid = 0;
      // our first pass used a single >=128 cutoff and only 1.6% of pixels cleared it, which tells
      // us nothing about whether the cutoff or the data is at fault. Bucket the whole range
      // instead, so the threshold gets chosen from the distribution rather than from taste
      int[] confidenceBuckets = new int[5];
      final int step = 2;
      for (int y = 0; y < height; y += step) {
        for (int x = 0; x < width; x += step) {
          sampled++;
          if ((depthBuffer.get(y * width + x) & 0xFFFF) == 0) {
            continue;
          }
          valid++;
          // confidence arrives as an unsigned byte in [0, 255].
          int confidence =
              confidenceBuffer.get(
                      y * confidencePlane.getRowStride() + x * confidencePlane.getPixelStride())
                  & 0xFF;
          confidenceBuckets[Math.min(confidence / 52, 4)]++;
        }
      }
      int scale = Math.max(valid, 1);
      return String.format(
          Locale.US,
          "raw %dx%d valid=%d%% conf[0-20|20-40|40-60|60-80|80-100]=%d/%d/%d/%d/%d%%",
          width,
          height,
          sampled == 0 ? 0 : (100 * valid / sampled),
          100 * confidenceBuckets[0] / scale,
          100 * confidenceBuckets[1] / scale,
          100 * confidenceBuckets[2] / scale,
          100 * confidenceBuckets[3] / scale,
          100 * confidenceBuckets[4] / scale);
    } catch (NotYetAvailableException e) {
      return "raw=not-yet-available";
    }
  }

  /**
   * Names the situation out loud, at most. SpeechManager decides whether it actually gets said -
   * this only has to describe what's true right now, in the same priority order the motor uses.
   */
  private void announceSituation() {
    if (channel == ModeArbiter.Channel.QUIET) {
      // scene mode: the device answers questions and otherwise says nothing. an imminent collision
      // never reaches here - the arbiter hands the channel to Guardian before that
      return;
    }
    String phrase = null;
    String situationKey = "";
    SpeechManager.Level level = SpeechManager.Level.MEDIUM;

    if (pointed != null && pointed.found) {
      // the name first, because that is the part a distance cannot replace
      String pointedPhrase =
          Float.isNaN(pointedMeters)
              ? "A " + pointed.label
              : String.format(
                  Locale.UK,
                  "A %s, %.1f metres",
                  pointed.label,
                  Math.round(pointedMeters * 2f) / 2f);
      speech.announce(SpeechManager.Level.HIGH, pointedPhrase);
      return;
    }

    boolean hazard =
        guardianReading != null && guardianReading.state == GuardianCorridor.State.HAZARD;
    boolean imminent = hazard && guardianReading.distanceMeters < IMMINENT_M;
    boolean cannotSee =
        guardianReading != null
            && (guardianReading.state == GuardianCorridor.State.BLIND
                || guardianReading.state == GuardianCorridor.State.STALE);
    ApertureScan.Gap gap = guardian.gap();

    // above every mode and every channel. Below a metre the wearer has less than a second, and
    // being given a routing option instead of a stop is worse than being given nothing.
    // "stop" means stop walking - useless shouted at someone standing still, which is what you do
    // while aiming the phone at something. HUD and haptics still fire, only the word is gated
    // A "stop" that turns out to be nothing costs more than a missed one, because after two of
    // them nobody listens to the third. So the loudest sentence in the app has to be backed by the
    // occupancy grid, not by one frame's percentile. On a logged corridor walk the percentile
    // reported a surface at 0.7-1.2m on 99% of frames and there was nothing there.
    boolean evidenceBacked =
        !guardian.beamHasEvidence()
            || guardian.beamSupportAt(guardianReading.distanceMeters) > BEAM_MIN_SUPPORT;
    if (imminent && motionBudget.speedMps() >= WALKING_MPS && evidenceBacked) {
      float rounded = Math.round(guardianReading.distanceMeters * 2f) / 2f;
      speech.announce(
          SpeechManager.Level.CRITICAL,
          String.format(
              Locale.UK, "Stop. %.1f metres, %s", rounded, side(guardianReading.lateralMeters)),
          "stop:" + side(guardianReading.lateralMeters));
      return;
    }

    // also above every channel. A frozen depth frame still fills the aperture fan and the fan will
    // go on confidently recommending a gap it measured seconds ago, so the admission has to outrank
    // the advice or the admission is decorative. This is the sentence no other system in this space
    // says out loud, and it names the cause so the wearer knows what to do about it
    if (cannotSee) {
      // "I cannot see" is the last thing to say, not the first.
      //
      // The backup channel needs about a second and a half to trigger and then a network round
      // trip, and announceSituation runs every frame in between. Left alone it announces blindness
      // first, every single time, and the useful answer arrives afterwards on top of it. So the
      // sentence is held back while the backup is still trying, and only spoken if that comes back
      // with nothing. Vibration carries the hazard throughout, so the wearer is not left with
      // nothing while we wait.
      long nowMs = System.currentTimeMillis();
      boolean answerFresh = fallbackAnswer != null && nowMs - fallbackAnswerMs < FALLBACK_FRESH_MS;
      boolean backupStillTrying =
          describer.isConfigured()
              && (describer.isBusy()
                  || (blindSinceMs != 0 && nowMs - blindSinceMs < FALLBACK_GRACE_MS));
      if (!answerFresh && !backupStillTrying) {
        speech.announce(
            SpeechManager.Level.HIGH,
            blindnessPhrase(guardian.blindness()),
            "blind:" + guardian.blindness());
      }
      return;
    }

    // Above everything, including the corridor's own reading.
    //
    // Something filling half the picture is at arm's length whatever depth thinks, and depth here
    // thinks 1.2 to 2.5 metres because it cannot see anything that close. Announcing a distance
    // then would be announcing a measurement we know to be wrong, so this says the only true thing
    // available: it is right there.
    String touching = objectMemory.contact(System.currentTimeMillis());
    if (touching != null) {
      speech.announce(
          SpeechManager.Level.CRITICAL,
          capitalise(touching) + " right in front of you",
          "contact:" + touching);
      return;
    }

    // above the routing advice, because the fan reads a staircase going down as a wide open gap and
    // will cheerfully send the wearer into it
    if (floorRunsOut()) {
      // The floor stopping means one of two things and we can often tell which.
      //
      // A staircase going down, or something standing there that depth could not see - a black
      // backpack has no texture to track and absorbs the light, so depth-from-motion reports an
      // empty corridor while the thing plainly occludes the floor behind it. In that case this is
      // the only channel that noticed, and the detector can supply the noun.
      String blocking =
          objectMemory.nameAt(floorEndsM, 0f, System.currentTimeMillis(), motionBudget.speedMps());
      speech.announce(
          SpeechManager.Level.CRITICAL,
          blocking == null
              ? String.format(Locale.UK, "Careful, ground stops %.1f metres ahead", floorEndsM)
              : String.format(Locale.UK, "Careful, %s ahead", blocking),
          "floorEnds:" + blocking);
      return;
    }

    switch (channel) {
      case TERRAIN:
        if (terrain.state() == TerrainWatch.State.DROP) {
          phrase =
              String.format(
                  Locale.UK, "Drop ahead, %.0f centimetres",
                  Math.abs(terrain.deviationMeters()) * 100);
          level = SpeechManager.Level.CRITICAL;
        } else {
          phrase = hazard ? "Stairs up" : "Step up";
          level = SpeechManager.Level.HIGH;
        }
        break;

      case APERTURE:
        // only when the advice has actually changed. a wearer walking towards a doorway looks at
        // the same doorway for several seconds, and repeating it turns guidance into nagging
        if (gap.fit == ApertureScan.Fit.UNKNOWN) {
          phrase = "I cannot read the space ahead";
          situationKey = "gapUnknown";
          level = SpeechManager.Level.HIGH;
        } else if (modes.gapAdviceIsNew(gap)) {
          phrase = ApertureScan.describe(gap);
          situationKey = "gap:" + gap.fit + ":" + Math.round(gap.bearingDeg / 15f);
          level =
              gap.fit == ApertureScan.Fit.WALK
                  ? SpeechManager.Level.MEDIUM
                  : SpeechManager.Level.HIGH;
        }
        break;

      default:
        if (hazard) {
          // rounded to the nearest half metre. "1.43 metres" is a different sentence
          // every frame, which would defeat the repeat cooldown and turn this into a stutter
          float rounded = Math.round(guardianReading.distanceMeters * 2f) / 2f;
          // name it when the detector can, because "chair" tells you to step round and "obstacle"
          // only tells you to stop. depth supplies the distance, colour supplies the noun
          String named = hazardName;
          phrase =
              String.format(
                  Locale.UK,
                  "%s %.1f metres, %s",
                  named == null ? "Obstacle" : capitalise(named),
                  rounded,
                  side(guardianReading.lateralMeters));
          // the noun, the half-metre band and the side. walking a chair down from four metres to
          // one is ONE situation until one of those three actually changes
          // naming the thing tells you to stop. naming the way round tells you what to do next,
          // and the fan already worked it out. only appended when the thing is actually in the way
          // and the fan is confident, or every sentence doubles in length for nothing
          String around = wayAround(gap, guardianReading);
          if (around != null) {
            phrase = phrase + ". " + around;
          }
          situationKey =
              "hazard:"
                  + (named == null ? "?" : named)
                  + ":"
                  + rounded
                  + ":"
                  + side(guardianReading.lateralMeters);
          level = SpeechManager.Level.HIGH;
        } else if (personOverride) {
          phrase = "Person ahead";
          situationKey = "person";
          level = SpeechManager.Level.HIGH;
        } else if (motionBudget.shouldAskForMotion()) {
          phrase = "Move your head";
          situationKey = "sway";
          level = SpeechManager.Level.MEDIUM;
        }
        break;
    }

    if (phrase != null) {
      speech.announce(level, phrase, situationKey);
    }
  }

  /**
   * Blindness with a cause attached. A mobility aid that goes quiet when its sensor dies is
   * indistinguishable from one reporting a clear path, which is how the wearer gets hurt. Naming
   * the reason is what turns the admission into an instruction.
   */
  private static String blindnessDetail(GuardianCorridor.Blindness why) {
    switch (why) {
      case TOO_FAST:
        return "moving faster than depth-from-motion can follow";
      case FROZEN:
        return "ARCore is serving the same depth frame";
      case INCOHERENT:
        return "near samples do not agree, something on the lens";
      case UNSTABLE:
        return "consecutive frames disagree about the distance";
      default:
        return "no usable depth";
    }
  }

  private static String blindnessPhrase(GuardianCorridor.Blindness why) {
    switch (why) {
      case TOO_FAST:
        return "I cannot see. You are moving too fast";
      case FROZEN:
        return "I cannot see. The sensor is frozen";
      case INCOHERENT:
        return "I cannot see. Something is right in front of the lens";
      case UNSTABLE:
        return "I cannot see. The readings disagree";
      default:
        return "I cannot see ahead";
    }
  }

  /**
   * Which way to step. 12 cm either side of centre before we commit to a direction - the corridor
   * is only 60 cm wide, so anything nearer the middle than that is simply in the way
   */
  /**
   * Largest CPU image ARCore will give us. The default is 640x480, where a water bottle at 3m is
   * about 12 pixels wide against the ~25 EfficientDet needs - and it is resampled to 448 after
   * that. Small floor clutter is the whole point, so pixels beat frame rate.
   */
  private void chooseHighestCpuImage() {
    if (session == null) {
      return;
    }
    try {
      CameraConfigFilter filter = new CameraConfigFilter(session);
      List<CameraConfig> configs = session.getSupportedCameraConfigs(filter);
      CameraConfig best = null;
      for (CameraConfig config : configs) {
        int w = config.getImageSize().getWidth();
        int h = config.getImageSize().getHeight();
        Log.i(TAG, "EYE_CAMERA option cpu=" + w + "x" + h
            + " gpu=" + config.getTextureSize().getWidth() + "x"
            + config.getTextureSize().getHeight()
            + " fps=" + config.getFpsRange() + " depth=" + config.getDepthSensorUsage());
        if (w > CPU_IMAGE_MAX_WIDTH) {
          // a full 1080p CPU frame has to be JPEG-encoded on the render thread every 200ms, and
          // that starves ARCore of the continuous frames depth-from-motion is built on: raw depth
          // validity fell from a 61-99% band to a median of zero
          continue;
        }
        if (best == null
            || w * h > best.getImageSize().getWidth() * best.getImageSize().getHeight()) {
          best = config;
        }
      }
      if (best != null) {
        session.setCameraConfig(best);
        Log.i(TAG, "EYE_CAMERA chose cpu image " + best.getImageSize().getWidth() + "x"
            + best.getImageSize().getHeight() + " of " + configs.size() + " configs");
      }
    } catch (Exception e) {
      Log.w(TAG, "camera config selection failed, staying on the default", e);
    }
  }

  /**
   * How bad depth thinks it is: 0 go, 1 careful, 2 stop.
   *
   * <p>Depth is the better judge of distance and a poor judge of existence. It measured 0% of
   * returns closer than 1.2m with a clear plastic bottle standing at 1m - light goes through it and
   * there is no texture to track, so the corridor reported clear and was telling the truth about
   * what it could see.
   */
  private static int depthSeverity(GuardianCorridor.Reading reading, boolean blindNearGround) {
    if (reading == null
        || reading.state == GuardianCorridor.State.BLIND
        || reading.state == GuardianCorridor.State.STALE) {
      return 1;
    }
    if (reading.state != GuardianCorridor.State.HAZARD) {
      return blindNearGround ? 1 : 0;
    }
    if (reading.distanceMeters < IMMINENT_M) {
      return 2;
    }
    return reading.distanceMeters < 2f ? 1 : 0;
  }

  /**
   * Two senses, and the more cautious one wins.
   *
   * <p>Neither is reliable alone. Depth cannot see clear plastic, glass or water; the model cannot
   * measure how far anything is. So the system only says go when both of them say go, and the
   * sentence the wearer hears always comes from the one that can name things.
   */
  private static String combineVerdicts(int depthSeverity, String modelText) {
    String text = modelText == null ? "" : modelText.trim();
    int modelSeverity = 0;
    String upper = text.toUpperCase(Locale.UK);
    if (upper.startsWith("STOP")) {
      modelSeverity = 2;
    } else if (upper.startsWith("CAREFUL")) {
      modelSeverity = 1;
    } else if (upper.startsWith("GO")) {
      modelSeverity = 0;
    } else {
      // no verdict word at all, so we have nothing to combine and fall back to the sensor
      modelSeverity = depthSeverity;
      text = "";
    }
    // strip the model's own verdict word - ours replaces it
    int cut = text.indexOf('.');
    String sentence = cut >= 0 && cut + 1 < text.length() ? text.substring(cut + 1).trim() : text;

    int worst = Math.max(depthSeverity, modelSeverity);
    String word = worst == 2 ? "Stop." : worst == 1 ? "Careful." : "Go ahead.";
    return sentence.isEmpty() ? word : word + " " + sentence;
  }

  /**
   * The half of the answer the model cannot produce: how far, and which way round.
   *
   * <p>"A chair is in front of you" is a description. "A chair is in front of you, one and a half
   * metres, clear to your left" is something a person can act on without asking a second question.
   */
  private String shotDetail(GuardianCorridor.Reading reading, ApertureScan.Gap gap) {
    if (reading == null || reading.state != GuardianCorridor.State.HAZARD) {
      return null;
    }
    float rounded = Math.round(reading.distanceMeters * 2f) / 2f;
    String detail = String.format(Locale.UK, "About %.1f metres.", rounded);
    String around = wayAround(gap, reading);
    return around == null ? detail : detail + " " + around + ".";
  }

  /** the measurement, handed to the model so its sentence agrees with the sensor */
  private static String depthNote(GuardianCorridor.Reading reading) {
    if (reading == null || reading.state != GuardianCorridor.State.HAZARD) {
      return "";
    }
    // for choosing which object to name, not for quoting. the model attached this number to the
    // wrong object once already - it named a bottle at arm's length and read out 1.3 metres,
    // which was the corridor's reading for something else entirely
    return String.format(
        Locale.UK,
        "The depth sensor measured the nearest obstacle at %.1f metres, %s. That is the thing to"
            + " name. Use it to choose which object you describe, and do not repeat the number.",
        reading.distanceMeters,
        side(reading.lateralMeters));
  }

  /**
   * What a spoken command does. Everything reachable by touch is reachable by voice, because a
   * mobility aid you drive by finding a button on a screen is a contradiction.
   */
  private void onVoiceCommand(VoiceCommands.Command command) {
    runOnUiThread(
        () -> {
          switch (command) {
            case MODE_SCENE:
              applyMode(ModeArbiter.Mode.SCENE);
              break;
            case MODE_GUARDIAN:
              applyMode(ModeArbiter.Mode.GUARDIAN);
              break;
            case MODE_APERTURE:
              applyMode(ModeArbiter.Mode.APERTURE);
              break;
            case MODE_AUTO:
              applyMode(ModeArbiter.Mode.AUTO);
              break;
            case DESCRIBE:
              // the same thing the L gesture does, for anyone who would rather not raise a hand
              describeRequested = true;
              break;
            case HELP:
              speech.announce(
                  SpeechManager.Level.REQUESTED,
                  "Say guardian, gap, scene, or auto to change mode. Say look to describe what is"
                      + " ahead.");
              break;
            case QUIET:
              speech.stop();
              haptics.stop();
              break;
          }
        });
  }

  private void applyMode(ModeArbiter.Mode mode) {
    modes.setMode(mode);
    modeButton.setText(ModeArbiter.shortName(mode));
    speech.announce(SpeechManager.Level.REQUESTED, ModeArbiter.announce(mode));
  }

  /**
   * True when the floor runs out before the free space does.
   *
   * <p>The one thing the aperture fan cannot see. It reads a staircase going down as a wide open
   * gap, because at body height that is exactly what a staircase is. The floor tells the other half
   * of the story: if you can walk four metres but the ground stops at one and a half, the ground is
   * where the answer is.
   */
  private boolean floorRunsOut() {
    if (Float.isNaN(floorEndsM)
        || guardian.floorSampleCount() < FLOOR_RUNOUT_MIN_SAMPLES) {
      return false;
    }
    // anything standing in the strip hides the floor behind it, and that is the ordinary reason
    // the floor stops - not a drop. so this only speaks when the corridor is genuinely empty and
    // the fan has nothing near to route around. without these two the warning fired at every
    // object the detector saw, which is the opposite of a drop
    if (guardianReading == null || guardianReading.state != GuardianCorridor.State.CLEAR) {
      return false;
    }
    ApertureScan.Gap gap = guardian.gap();
    if (gap.coverage < 0.6f || gap.barrierM < FLOOR_RUNOUT_CLEAR_BARRIER_M) {
      return false;
    }
    return gap.clearAheadM - floorEndsM > FLOOR_RUNOUT_M;
  }

  /**
   * Turns the backup model's closed-set answer into something a person would say.
   *
   * <p>The model replies in a fixed vocabulary so the app can act on it: CLEAR, OBSTACLE chair,
   * PERSON, STAIRS, DROP, DOORWAY. Reading that out verbatim gives you "backup vision, obstacle
   * chair", which is a protocol, not a warning.
   */
  private static String spokenFallback(String answer) {
    if (answer == null || answer.trim().isEmpty()) {
      return "Backup vision cannot tell";
    }
    String text = answer.trim();
    String upper = text.toUpperCase(Locale.UK);
    if (upper.startsWith("CLEAR")) {
      return "Clear ahead";
    }
    if (upper.startsWith("PERSON")) {
      return "Person ahead";
    }
    if (upper.startsWith("STAIRS")) {
      return "Stairs ahead";
    }
    if (upper.startsWith("DROP")) {
      return "Drop ahead, stop";
    }
    if (upper.startsWith("DOORWAY")) {
      return "Doorway ahead";
    }
    if (upper.startsWith("OBSTACLE")) {
      String what = text.substring("OBSTACLE".length()).trim();
      return what.isEmpty() ? "Obstacle ahead" : capitalise(what) + " ahead";
    }
    return text;
  }

  /**
   * Which way round the thing in the corridor, from the free-space fan, or null when there is
   * nothing useful to add.
   *
   * <p>Kept quiet unless the obstacle is genuinely in the path and the fan trusts itself. A
   * direction offered around something the wearer was already going to walk past is noise.
   */
  private String wayAround(ApertureScan.Gap gap, GuardianCorridor.Reading reading) {
    if (gap == null || reading == null) {
      return null;
    }
    if (Math.abs(reading.lateralMeters) > 0.18f) {
      // already off to one side, so the wearer is not walking into it
      return null;
    }
    if (gap.fit != ApertureScan.Fit.WALK || gap.coverage < 0.6f) {
      return null;
    }
    if (Math.abs(gap.bearingDeg) < 8f) {
      return null;
    }
    return gap.bearingDeg < 0 ? "Clear to your left" : "Clear to your right";
  }

  private static String capitalise(String word) {
    return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
  }

  /**
   * Hand every detection this frame to the memory, with a depth reading where one exists.
   *
   * <p>Depth and colour are sampled through ARCore's own coordinate transform rather than by
   * projecting boxes by hand. The hand-rolled version fired on 1 frame in 72, because the detector
   * works on a bitmap straightened for the current grip and that grip changes as the phone tilts.
   */
  private void rememberObjects(Frame frame, Image depthImage, long nowMs) {
    if (vision == null) {
      return;
    }
    float[] boxes = vision.objectBoxes();
    String[] labels = vision.objectLabels();
    int count = Math.min(vision.objectCount(), labels.length);
    for (int i = 0; i < count; i++) {
      if (labels[i] == null || "person".equals(labels[i])) {
        // people have their own channel and their own sentence
        continue;
      }
      boxCentre[0] = (boxes[i * 4] + boxes[i * 4 + 2]) / 2f;
      boxCentre[1] = (boxes[i * 4 + 1] + boxes[i * 4 + 3]) / 2f;
      frame.transformCoordinates2d(
          Coordinates2d.IMAGE_NORMALIZED, boxCentre, Coordinates2d.TEXTURE_NORMALIZED,
          boxCentreTex);
      float metres = sampleDepth(depthImage, boxCentreTex[0], boxCentreTex[1]);
      // sideways offset from the middle of the picture, scaled to metres at that distance. rough,
      // and only used to reject a track sitting on the opposite side of the corridor
      float lateral = Float.isNaN(metres) ? 0f : (boxCentre[0] - 0.5f) * metres;
      float share =
          Math.abs((boxes[i * 4 + 2] - boxes[i * 4]) * (boxes[i * 4 + 3] - boxes[i * 4 + 1]));
      objectMemory.observe(
          labels[i], boxCentre[0], boxCentre[1], metres, lateral, share, nowMs);
    }
  }

  /** depth off and straight back on - keeps the preview up while the depth pipeline restarts */
  private void recoverDepth() {
    if (session == null) {
      return;
    }
    try {
      Config config = session.getConfig();
      config.setDepthMode(Config.DepthMode.DISABLED);
      session.configure(config);
      config.setDepthMode(Config.DepthMode.AUTOMATIC);
      session.configure(config);
    } catch (Exception e) {
      Log.w(TAG, "depth recovery failed", e);
    }
  }

  /**
   * Last resort when the depth-mode toggle will not shift a frozen stream. Tears the camera down
   * and back up, which the toggle does not, and blanks the preview while it happens.
   */
  private void restartSession() {
    if (session == null) {
      return;
    }
    try {
      session.pause();
      session.resume();
    } catch (Exception e) {
      Log.w(TAG, "session restart failed", e);
    }
  }

  /** median of a small patch, so one dead pixel in the middle of a box doesn't decide the answer */
  private static float sampleDepth(Image depthImage, float u, float v) {
    if (u < 0 || u >= 1 || v < 0 || v >= 1) {
      return Float.NaN;
    }
    int width = depthImage.getWidth();
    int height = depthImage.getHeight();
    java.nio.ShortBuffer depth =
        depthImage
            .getPlanes()[0]
            .getBuffer()
            .order(java.nio.ByteOrder.nativeOrder())
            .asShortBuffer();
    int cx = (int) (u * width);
    int cy = (int) (v * height);
    int[] samples = new int[49];
    int n = 0;
    for (int dy = -3; dy <= 3; dy++) {
      for (int dx = -3; dx <= 3; dx++) {
        int x = cx + dx;
        int y = cy + dy;
        if (x < 0 || x >= width || y < 0 || y >= height) {
          continue;
        }
        int mm = depth.get(y * width + x) & 0xFFFF;
        if (mm > 200 && mm < 8000) {
          samples[n++] = mm;
        }
      }
    }
    if (n < 5) {
      return Float.NaN;
    }
    int[] sorted = java.util.Arrays.copyOf(samples, n);
    java.util.Arrays.sort(sorted);
    return sorted[n / 2] / 1000f;
  }

  /**
   * Which way to turn the camera frame so the room comes out upright, from gravity.
   *
   * <p>Hardcoded at 90 this was right for exactly one grip. Held any other way the detector got a
   * sideways room and missed a chair in plain view. Rounded to quarter turns - all a cheap bitmap
   * rotation can do, and a head is never far off one.
   */
  private static int uprightDegrees(float[] upInCamera) {
    // two turns added: the sensor sits a quarter turn out from the phone's upright (a constant,
    // and why the hardcoded 90 ever worked), plus however far the head is tilted
    // using only the second half gave rot=0 on an upright phone, i.e. the raw sensor frame
    double tilt = Math.toDegrees(Math.atan2(upInCamera[0], upInCamera[1]));
    int quarter = (int) (Math.round((SENSOR_MOUNT_DEGREES + tilt) / 90.0) * 90);
    return ((quarter % 360) + 360) % 360;
  }

  private static float clamp01(float value) {
    return value < 0f ? 0f : (value > 1f ? 1f : value);
  }

  private static String side(float lateralMeters) {
    if (lateralMeters < -0.12f) {
      return "on your left";
    }
    if (lateralMeters > 0.12f) {
      return "on your right";
    }
    return "straight ahead";
  }

  /**
   * onDrawFrame runs 30 times a second and this hops to the UI thread, so only push when the text
   * actually changed - otherwise the main thread spends its life re-laying-out identical strings
   */
  /**
   * Light one of the three direction segments, or none.
   *
   * <p>Which side a thing is on is the only part of this the wearer can act on immediately, and the
   * only part a room watching a mirrored screen can read from the back row. It gets its own row
   * rather than being buried in a sentence.
   *
   * @param lit -1 left, 0 straight ahead, 1 right, anything else for none
   */
  private void updateDirection(int lit, int colour) {
    if (lit == lastLitDirection) {
      return;
    }
    lastLitDirection = lit;
    runOnUiThread(
        () -> {
          paintDirection(dirLeft, lit == -1, colour);
          paintDirection(dirCentre, lit == 0, colour);
          paintDirection(dirRight, lit == 1, colour);
        });
  }

  private void paintDirection(TextView view, boolean on, int colour) {
    if (view == null) {
      return;
    }
    view.setTextColor(on ? colour : 0xFF4A4A55);
    view.getBackground().setTint(on ? (colour & 0x30FFFFFF) : 0xFF14141A);
  }

  /** which segment a sideways offset belongs to, using the same edges the spoken side() does */
  private static int directionOf(float lateralMeters) {
    if (lateralMeters < -0.12f) {
      return -1;
    }
    return lateralMeters > 0.12f ? 1 : 0;
  }

  private void updateHud(String state, String detail, int color) {
    // the strip that makes the arbitration visible: mode on the left of the slash is what the
    // wearer chose, the channel on the right is who the device actually gave the output to
    String chan =
        ModeArbiter.shortName(modes.mode())
            + "  \u2022  "
            + channel
            + "  \u2022  "
            + modes.explain(guardian.gap(), guardian.floorSampleCount());
    if (state.equals(lastHudState) && detail.equals(lastHudDetail) && chan.equals(lastHudChannel)) {
      return;
    }
    lastHudState = state;
    lastHudDetail = detail;
    lastHudChannel = chan;
    runOnUiThread(
        () -> {
          hudState.setText(state);
          hudState.setTextColor(color);
          hudDetail.setText(detail);
          hudChannel.setText(chan);
        });
  }

  /** Checks if we detected at least one plane. */
  private boolean hasTrackingPlane() {
    for (Plane plane : session.getAllTrackables(Plane.class)) {
      if (plane.getTrackingState() == TrackingState.TRACKING) {
        return true;
      }
    }
    return false;
  }

  /** Update state based on the current frame's light estimation. */
  private void updateLightEstimation(LightEstimate lightEstimate, float[] viewMatrix) {
    if (lightEstimate.getState() != LightEstimate.State.VALID) {
      virtualObjectShader.setBool("u_LightEstimateIsValid", false);
      return;
    }
    virtualObjectShader.setBool("u_LightEstimateIsValid", true);

    Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0);
    virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix);

    updateMainLight(
        lightEstimate.getEnvironmentalHdrMainLightDirection(),
        lightEstimate.getEnvironmentalHdrMainLightIntensity(),
        viewMatrix);
    updateSphericalHarmonicsCoefficients(
        lightEstimate.getEnvironmentalHdrAmbientSphericalHarmonics());
    cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap());
  }

  private void updateMainLight(float[] direction, float[] intensity, float[] viewMatrix) {
    // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
    worldLightDirection[0] = direction[0];
    worldLightDirection[1] = direction[1];
    worldLightDirection[2] = direction[2];
    Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0);
    virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection);
    virtualObjectShader.setVec3("u_LightIntensity", intensity);
  }

  private void updateSphericalHarmonicsCoefficients(float[] coefficients) {
    // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
    // constants in sphericalHarmonicFactors were derived from three terms:
    //
    // 1. The normalized spherical harmonics basis functions (y_lm)
    //
    // 2. The lambertian diffuse BRDF factor (1/pi)
    //
    // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
    // of all incoming light over a hemisphere for a given surface normal, which is what the shader
    // (environmental_hdr.frag) expects.
    //
    // You can read more details about the math here:
    // https://google.github.io/filament/Filament.html#annex/sphericalharmonics

    if (coefficients.length != 9 * 3) {
      throw new IllegalArgumentException(
          "The given coefficients array must be of length 27 (3 components per 9 coefficients");
    }

    // Apply each factor to every component of each coefficient
    for (int i = 0; i < 9 * 3; ++i) {
      sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3];
    }
    virtualObjectShader.setVec3Array(
        "u_SphericalHarmonicsCoefficients", sphericalHarmonicsCoefficients);
  }

  /** Configures the session with feature settings. */
  private void configureSession() {
    Config config = session.getConfig();

    // we tried FocusMode.AUTO to get sharp close-up hands for MediaPipe and it wrecked depth on
    // device - readings jumped 2m, 13m, garbage, frame to frame, because every focus hunt moves the
    // lens and depth-from-motion reads that as the world moving. fixed focus stays. the cost is
    // that hands closer than about 30cm are soft, which we work around by not needing them sharp
    config.setFocusMode(Config.FocusMode.FIXED);

    // the sample lit its virtual pawns with this. we deleted the pawns, and estimating environmental
    // lighting every frame is not free, so it's off
    config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      config.setDepthMode(Config.DepthMode.AUTOMATIC);
    } else {
      config.setDepthMode(Config.DepthMode.DISABLED);
    }
    if (instantPlacementSettings.isInstantPlacementEnabled()) {
      config.setInstantPlacementMode(InstantPlacementMode.LOCAL_Y_UP);
    } else {
      config.setInstantPlacementMode(InstantPlacementMode.DISABLED);
    }
    session.configure(config);
  }
}

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
class WrappedAnchor {
  private Anchor anchor;
  private Trackable trackable;

  public WrappedAnchor(Anchor anchor, Trackable trackable) {
    this.anchor = anchor;
    this.trackable = trackable;
  }

  public Anchor getAnchor() {
    return anchor;
  }

  public Trackable getTrackable() {
    return trackable;
  }
}
