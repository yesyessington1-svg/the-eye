# The Eye

**An intelligent visual-to-haptic transduction system for spatial perception and assisted
navigation, built to reduce collisions for people with visual impairments.**

The platform analyses the space around the wearer and offers several possible navigation responses
rather than a single alarm. It implements self-guidance through acoustic and vibratory signals: the
phone becomes the main perception point for the surrounding environment and identifies redirection
options when the wearer would otherwise be forced to stop.

The wearer can ask it a question at any time, either by a haptic gesture shaped like a reversed L
formed with the index finger and thumb, or by voice.

Running underneath that, continuously, the collision-prevention channel measures the space
accessible to the wearer's body and estimates whether there is room to keep moving. Rather than
treating every detection as certain, it weights what it is told by the confidence its own sensors
report, which is what keeps false and inaccurate warnings down.

So The Eye is not an obstacle detector. It turns visual information into a spatial representation
delivered through vibration and sound, which is a different way for a person to perceive and
interpret the space they are standing in.

### Team

| | |
|---|---|
| **Ștefan Crețu** | scientific research framing and research direction |
| **Sandu Rareș** | creativity, vision, and design of the prototype |
| **Lupu George** | full-stack development |

---

## The problem

A white cane reads the ground one step ahead. It cannot tell you that the corridor narrows in three
metres, that the gap between two people is too tight, or that the thing ahead is a suitcase rather
than a wall. Existing electronic aids mostly encode obstacles: *something is 1.2 m away at your ten
o'clock.* Two things go wrong with that.

1. **It is the wrong object.** Virtual Whiskers (arXiv:2408.14550, 10 blind participants) found
 that encoding the obstacle field performed no better than nothing, while encoding *free space*
 cut cane contacts by 70-80%. The gap is the actionable thing, not the obstacle.
2. **It fails silently.** When the sensor stops working the corridor reads empty and the device
 says nothing, which the wearer correctly hears as "clear". That is the most dangerous default a
 mobility aid can have, and it is the common one.

## Where the idea came from

We started by building the obvious thing (head-mounted phone, depth sensor, buzz when something is
close) and then found it had already been built and had already lost. Neugebauer et al. (PLOS ONE
2020, doi:10.1371/journal.pone.0237344) put a phone on a blind user's forehead, ran ARCore depth,
sonified it, and lost to a white cane on every measure: 68.4 s versus 27.0 s on the course, 2.96
versus 0.78 collisions, after four hours of training.

Repeating a published failure is not a project, so we kept the hardware and changed the question:

- **from "what is in the way" to "where is there room"** - a 1-D fan of free distances, and the
 widest run of directions that stays open, measured against shoulder width. The 1.22 ratio comes
 from de Paz et al. (PLoS ONE 2019, doi:10.1371/journal.pone.0213342), who measured where blind
 walkers start turning their shoulders
- **from silent failure to attributed failure** - every blind frame carries a cause, and the wearer
 hears it instead of hearing nothing
- **from one sense to two that check each other** - ARCore's depth cannot see clear plastic or
 glass, a vision model cannot measure distance, so the system only says *go* when both agree

Output stays small on purpose. Frontiers in ICT 2017 (doi:10.3389/fict.2017.00023) found a walking
user can hold about two simultaneously-coded parameters before the display stops being useful, so
there is no third haptic channel.

---

## How it works

### The pipeline, one frame at a time

Every frame (30 Hz) runs the same path. The expensive parts are throttled or moved off the render
thread, because ARCore needs continuous frames to compute depth at all.

```
ARCore frame
├─ depth image (160x90, 16-bit mm)
│ └─ GuardianCorridor.evaluate()
│ ├─ every pixel -> (lateral, height, ground distance) against GRAVITY, not the phone
│ ├─ cut the wearer's own body (a wedge below and close to the camera)
│ ├─ collect floor samples -> floor height, nearest floor, furthest floor
│ ├─ keep what lands in a body-sized box -> nearest thing, which side, how many samples
│ └─ feed every slab point to ApertureScan
│ └─ 41 bins x 1.5 deg -> free distance per bearing -> widest open run -> fit
│
├─ camera image (1920x1080, throttled to 5 Hz, converted off the render thread)
│ └─ VisionDetector
│ ├─ HandLandmarker -> is the wearer making an L, or pointing
│ └─ EfficientDet-Lite2 -> object boxes, filtered to 33 mobility-relevant COCO classes
│
├─ ModeArbiter.decide() -> which channel owns the output this frame
│
└─ output
 ├─ HapticEngine -> a double buzz whose repeat rate climbs as things get closer
 ├─ SpeechManager -> rationed: vibration carries state, speech carries change
 └─ HUD -> what the algorithm sees, for anyone watching a mirrored screen
```

### The four channels, and who wins

`ModeArbiter` does not decide what is out there. It decides **who gets to speak**, from each
sense's own confidence rather than a fixed priority list.

| Channel | Owns the output when | Says |
|---|---|---|
| `GUARDIAN` | something is inside the body corridor | "backpack, 0.9 metres, on your left" |
| `APERTURE` | the fan read more than 60% of itself and the nearest barrier is 1.2-2.5 m away | "gap, slightly left" |
| `TERRAIN` | disabled, see below | - |
| `QUIET` | nothing worth saying | nothing |

One rule outranks all of it: an obstacle inside stopping distance is not a routing problem, so it
takes the channel in every mode.

### The geometry that makes it work

Everything vertical is measured against **gravity**, not against the phone. ARCore's pose gives
world-up in camera coordinates, so a point's height is how far it lies along that vector:

```java
aboveCamera = pointX*up[0] + pointY*up[1] - z*up[2];
ground = sqrt(range² - aboveCamera²);
```

Without this the corridor is only correct when the phone happens to be level, which it never is on
a person. With it, tilting the phone down to look for floor clutter no longer tilts the corridor
into the floor.

The corridor's bottom edge is anchored to the **measured floor**, not to the wearer's head. A fixed
offset from a forehead camera puts the corridor floor 65 cm above the ground, which makes a bottle
or a bag structurally invisible rather than merely missed.

### Free-space encoding

`ApertureScan` collapses the depth image into a fan of 41 bins, 1.5° each. Each bin holds the three
nearest returns at body height; the third one is the one we believe, so one hot pixel cannot build
a wall. Then:

1. find the nearest barrier anywhere in the fan
2. a direction is *open* if it reaches 60 cm past that barrier - relative, because "is this
 direction far" is the wrong question and "does this get me past the thing in my way" is the
 right one
3. take the widest run of open directions, measure its width at the nearer of the two things
 bounding it, and compare that to shoulder width

Walk / squeeze / blocked, plus a bearing. Validated against synthetic doorways on a desktop JVM
before any device build (`docs/ApertureSim.java`).

### Two senses, and the cautious one wins

The scene description combines a vision-language model with the depth reading:

- the model names things and is forbidden from stating any distance, because it cannot measure one
- the corridor supplies the distance and the verdict
- the more cautious of the two verdicts is what the wearer hears

This exists because of a measured failure: with a clear plastic bottle standing at 1 m, **0% of
centre-frame depth returns were closer than 1.2 m**. Light passes through it and there is nothing
to track. The sensor said clear and was telling the truth about what it could see; the model saw
the bottle perfectly.

---

## Using it

Grant camera and microphone on first launch.

**Hold the phone at chest or forehead height, camera forward, tilted about 25-30° down.** This is
not a preference. A depth image spans about 40° top to bottom, so held level at head height the
camera sees ±36 cm about its own axis at one metre, and a 90 cm cabinet has its top 60 cm below
that. The app measures where its own blind zone ends and puts `TILT DOWN` on screen when the floor
is not in view.

### Controls

Everything is reachable by voice, because a mobility aid you drive by finding a button on a screen
is a contradiction. Tap the mode pill if you prefer.

| Say | What happens |
|---|---|
| "guardian" | corridor mode: nearest obstacle, named, with distance and side |
| "gap" | free-space mode: which way there is room, and whether you fit |
| "scene" | description mode |
| "auto" | the arbiter picks, frame by frame |
| "look" | describe what is ahead and whether to walk |
| "help" | reads the commands back |
| "quiet" | stops speech and vibration |

Making an **L with thumb and index finger** does the same as "look". Hold it for about half a
second.

### What the screen shows

Top left is the selected mode, then a green dot when voice is listening. The bottom card carries
the channel that currently owns the output, a three-segment direction bar, what was found, and how
far away it is.

### Reading a session afterwards

Every frame is written to a CSV on the phone. Pull it and plot it:

```bash
adb pull /sdcard/Android/data/com.google.ar.core.examples.java.helloar/files/sessions/ .
python3 docs/plot_session.py sessions/reach-<timestamp>.csv
```

Six plots, including a bird's-eye view of every obstacle where it actually was, with the wearer at
the origin. This is how we found that 14% of hazard frames were reporting a distance of zero, which
was invisible in 780 lines of log text.

---

## What we measured

Every number came out of the device's own logs, not out of how it felt. Full notes in
`docs/MEASUREMENTS.md`.

| | before | after |
|---|---|---|
| depth unusable (blind or frozen) | 39% of frames | 1 frame in 116 |
| longest depth freeze | 68 s | none |
| free space reported straight ahead | 0.79 m median | 4.00 m median |
| frames claiming an obstacle under 0.6 m | most | 0 of 116 |
| fan coverage while tilted at the floor | 37% median | 100% |
| the wearer's own body counted as an obstacle | 48% of frames | suppressed |
| hazard frames reporting a distance of zero | 14% | 0 |

Things we measured and could not fix, written into the source where they matter:

- **ARCore cannot see clear plastic.** 0% of centre depth closer than 1.2 m with a bottle at 1 m.
 This is why the model's verdict can override the sensor's.
- **A level camera at head height cannot see the floor.** The blind zone runs to about four metres.
 The app computes where it ends and says so.
- **Drop detection by height comparison is off**, after three rewrites. The last one announced
 "step up 11 cm" at the edge of a rug across 107 stable frames. A few hundred pixels at the bottom
 of a 160x90 image cannot resolve ten centimetres. What replaced it asks a coarser question the
 data can actually answer: does the floor run out before the free space does.

---

## Stack

- **ARCore 1.54** - depth from motion, raw depth plus its confidence plane as a failover, camera
 pose for gravity, `transformCoordinates2d` for image-to-depth mapping
- **MediaPipe Tasks Vision 1.0** - HandLandmarker and EfficientDet-Lite2, CPU delegate, one worker
- **OpenAI vision API** - the scene description, the only part that leaves the device
- **Android TextToSpeech and SpeechRecognizer** - spoken output and spoken control
- **Java 17, OpenGL ES 2.0**, no third-party UI framework

Built on Google's `hello_ar_java` sample (Apache 2.0). The AR rendering scaffolding is theirs.
`GuardianCorridor`, `ApertureScan`, `ModeArbiter`, `VisionDetector`, `SceneDescriber`,
`HapticEngine`, `SpeechManager`, `MotionBudget`, `TerrainWatch`, `PointedObject`, `MonoDepth`,
`VoiceCommands`, `SessionRecorder`, `DetectionOverlayView` and `YuvToBitmap` are ours.

---

## Running it locally

You need an ARCore-supported Android phone with Depth API support, Android Studio, and JDK 17.

```bash
git clone https://github.com/yesyessington1-svg/reach.git
cd reach
```

Create `local.properties` in the project root:

```properties
sdk.dir=/path/to/your/Android/Sdk
openaiApiKey=sk-... # optional, only the scene description needs it
```

Build and install:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, or if the JDK on your PATH is not 17:

```bat
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug
```

The API key lands inside the APK. That is acceptable for a demo we hand to nobody and wrong for
anything shipped; the real fix is a server we did not have time to build.

---

## Honest limits

- Tested by sighted developers. No blind user and no orientation-and-mobility instructor has used
 it, which is the biggest gap in the work.
- Clear and reflective objects are invisible to the depth sensor.
- The phone needs a mount that holds the tilt. We tested it handheld.
- Object labels come from COCO's 80 classes, filtered to the 33 a walker can trip over. Anything
 outside that list is reported as "obstacle" rather than named wrong.
- Depth comes from motion, so standing perfectly still degrades it. The app asks the wearer to sway
 rather than going quietly blind.
