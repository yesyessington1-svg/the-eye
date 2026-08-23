# The Eye - full technical overview

Everything in one file, for diagramming. Numbers are measured on a Galaxy S25 Ultra (no ToF).

---

## 0. Hardware and inputs

| Stream | Size | Rate | Notes |
|---|---|---|---|
| Smoothed depth | 160x90, 16-bit mm | 30 Hz | gap-free, always reports 100% valid, **cannot say where it guessed** |
| Raw depth | 160x90, 16-bit mm | 30 Hz | leaves genuine holes |
| Raw confidence | 160x90, 8-bit | 30 Hz | per-pixel 0-255, the only honesty signal in the system |
| Camera CPU image | 1920x1080 | 5 Hz used | ARCore default is 640x480, we request the largest |
| Camera pose | 6-DoF | 30 Hz | gives gravity and per-frame travel |

Depth here is **depth-from-motion**: no depth sensor exists on this phone. Parallax between frames
is the entire measurement. Standing still degrades it; contact range breaks it.

---

## 1. Frame pipeline

```
ARCore Frame (30 Hz)
│
├── pose ─────────► gravity vector (world-up in camera coords)
│                   per-frame travel d
│
├── smoothed depth ─► GuardianCorridor.evaluate()
│                     └─► every pixel → (lateral, height, ground) vs GRAVITY
│                         ├─ self-cut
│                         ├─ floor sampling
│                         ├─ corridor test → nearest, side, hit count
│                         └─ every slab point → ApertureScan
│
├── raw depth + confidence ─► OccupancyBeam (log-odds evidence grid)
│
├── camera image (5 Hz, converted off the render thread)
│   └─► VisionDetector
│       ├─ HandLandmarker  → L gesture / pointing
│       └─ EfficientDet-Lite2 → boxes, filtered to 33 mobility classes
│           └─► ObjectMemory (tracks with motion prediction)
│
├── ModeArbiter.decide() → which channel owns the output
│
└── output
    ├─ HapticEngine  (state)
    ├─ SpeechManager (change only)
    ├─ HUD           (what the algorithm sees)
    └─ SessionRecorder → CSV → plots
```

---

## 2. Gravity-referenced geometry

The load-bearing idea. Everything vertical is measured against gravity, never against the phone.

Given a depth pixel `(x, y)` with depth `z` and camera intrinsics `fx, fy, cx, cy`:

```
pointX = z (x - cx) / fx          sideways, metres
pointY = z (cy - y) / fy          up in CAMERA space, metres
```

ARCore gives `up`, the world-up vector expressed in camera coordinates. The camera looks down `-Z`,
so the point is `(pointX, pointY, -z)` and:

```
aboveCamera = pointX·up[0] + pointY·up[1] - z·up[2]
range²      = pointX² + pointY² + z²
ground      = sqrt(range² - aboveCamera²)
```

`aboveCamera` is height along gravity. `ground` is horizontal distance. Neither depends on how the
phone is tilted.

**Why:** without this the corridor is only correct when the phone is level, which it never is on a
person. Tilt the phone down to see the floor and a camera-referenced corridor tilts into the floor.

---

## 3. The corridor (GuardianCorridor)

A box the size of the space the wearer is about to walk through.

```
        top = +0.20 m ┌─────────────┐
                      │             │
   camera ────────────┤             │  half width = 0.30 m
                      │             │  far = 2.00 m
     bottom = floor   └─────────────┘
            + 0.15 m
```

| Constant | Value | Reason |
|---|---|---|
| `HALF_WIDTH_M` | 0.30 | shoulder half-width plus margin |
| `TOP_M` | +0.20 | clears door frames |
| bottom | measured floor + 0.15 | see below |
| `FAR_M` | 2.00 | corridor's own range |
| `SCAN_FAR_M` | 6.00 | floor sampling and the fan need more |
| `MIN_HITS` | 12 | below this it is noise |

**Floor anchoring.** The bottom edge follows the measured floor, not the wearer's head. A fixed
-0.90 m from a forehead camera puts the corridor floor 65 cm above the ground, which makes a bottle
or a bag *structurally invisible*, not merely missed.

Floor height = 30th percentile of collected floor samples, sorted most-negative first. The 60th
percentile estimated the floor as wherever the clutter was.

**Self-cut.** Points with `ground < 0.35`, or `ground < 0.45 and aboveCamera < -0.40`, are the
wearer's own body. Measured: frames flagged INCOHERENT carried 2077 self points and a 0.31 m
nearest return; all other frames, 33 and 0.95 m.

**Reported distance** = 5th percentile of corridor hits, not the minimum. The single nearest pixel
wandered 0.3 m frame to frame on a static scene.

**States:** CLEAR, HAZARD, BLIND, STALE. Blindness carries a cause: TOO_FAST, INCOHERENT, UNSTABLE,
FROZEN.

---

## 4. Free-space encoding (ApertureScan)

Answers "where is there room", not "what is in the way".

```
        41 bins × 1.5° = 61.5° fan

         \  |  /              each bin holds the 3 nearest
          \ | /               returns at body height;
    ───────●───────           the 3rd is the one believed
```

| Constant | Value |
|---|---|
| bins | 41 × 1.5° |
| range | 0.35 - 4.00 m |
| `OPEN_MARGIN_M` | 0.60 |
| `SHOULDER_M` | 0.45 |
| `RATIO_WALK` | 1.22 |
| `RATIO_SQUEEZE` | 1.00 |

**Algorithm**

1. `barrier = min over all bins of freeDistance(bin)`
2. `need = barrier + 0.60`   ← a heading must get you PAST the barrier, not merely as far as it
3. **configuration space:** grow every obstacle by the body's radius
4. any bearing whose dilated free distance ≥ `need` is passable **by construction**
5. among passable bearings, take the one closest to straight ahead
6. if none: repeat with the shoulders-turned radius → SQUEEZE
7. if still none: BLOCKED

### Configuration space, step 3

The step that turned this from a measurement into an instruction.

```
an obstacle at distance d blocks every bearing within

        θ = arcsin(r / d)

of itself, where r = 0.30 m (half a shoulder plus a hand's width)
```

| obstacle at | blocks |
|---|---|
| 0.5 m | ±37° |
| 1.0 m | ±17.5° |
| 2.0 m | ±8.6° |
| 3.0 m | ±5.7° |

Close things block a wide arc, distant things a narrow one. A width comparison cannot express that.

**Why it replaced width-matching.** The old version found the widest run of open bins, converted it
to metres at the pinch distance, and compared that to a shoulder. A logged run in a cluttered room
reported a **median gap width of 0.23 m against a 0.45 m shoulder**, so the answer was always
"narrow" and never a direction. Asking about the gap is the wrong question; asking which bearings
the body fits along is the right one, and after dilation that question needs no comparison at all.

From Lozano-Perez's configuration space, and the step Borenstein & Koren's VFH (1991) performs
before choosing a heading.

**Validated against the demo scenario** (backpacks on the floor, camera at 1.35 m):

| scene | verdict |
|---|---|
| one bag dead ahead at 2 m | WALK, -16.5° |
| two bags, 1.0 m clear gap | WALK, 0° (straight through) |
| two bags, 0.8 m clear gap | WALK, 0° |
| two bags, 0.6 m gap (= body diameter) | routes around, no margin to pass |
| two bags, 0.3 m gap | routes around |
| slalom, bag left at 1.5 m then right at 2.8 m | WALK, 0° |
| four bags in a line | SQUEEZE at the rim, flagged as edge-of-view |

And against doorways: 0.80 m at 2.5 m → WALK ahead; 0.90 m offset 0.6 m left → WALK at -12°;
0.55 m → SQUEEZE; 0.30 m → BLOCKED; solid wall at 1.5 m → BLOCKED, at 3.5 m → clear.

**Openness is relative, not absolute.** A fixed 2 m threshold made a doorway at 2.5 m vanish,
because every direction cleared the bar and the whole fan read as one gap. The question is not "is
this direction far" but "does this direction get me past the thing in my way".

**Edge runs are never BLOCKED.** A run reaching the fan edge does not end there; the sensor stops
at 31°, the world does not. `width` is a lower bound, so "too narrow to fit" is unsupported. One
30 cm bottle at 1 m in an empty room read BLOCKED before this.

**Coverage** counts any depth return in a bearing, including floor. Visible floor out to 3 m proves
nothing is standing there, because anything standing there would have hidden it.

**Ground:** de Paz et al., PLoS ONE 2019, doi:10.1371/journal.pone.0213342 - blind walkers judge an
aperture against their own shoulders and start rotating at a ratio near 1.22.

---

## 5. Occupancy grid (OccupancyBeam)

The fix for a sensor that lies *consistently*.

**Measured failure:** walking a corridor with windows, 90% of pixels in the lowest confidence band,
depth reported a surface at 0.7-1.2 m on 61%, 91% and 99% of three consecutive frames. Nothing was
there. Median filtering cannot help, because every frame agrees.

**Model:** Moravec & Elfes 1985, inverse sensor model, log-odds.

```
corridor split into 37 cells of 0.10 m, from 0.30 to 4.00 m

a return at range d updates:
    cells before d   →  L_FREE     = -0.30 × w      (the ray got there: empty)
    cell at d        →  L_OCCUPIED = +0.85 × w
    cells beyond d   →  unchanged                    (unknown)

clamped to ±4.0
```

**The half people forget** is the first line. A frame reading 2.0 m does not merely outvote a
phantom at 0.9 m, it **cancels** it, because the ray passed through that cell.

**Confidence weighting**

```
w = (confidence / 255)²
```

Squared, not linear. The phantom field sat at confidence ~10, so `w ≈ 0.0015`. A real wall at
confidence 60 gives `w ≈ 0.055`, forty times more.

**Ego-motion shift.** Each frame the cells slide by the wearer's actual travel `d`, with linear
interpolation between cells:

```
newCell[i] = old[⌊i + d/0.10⌋]·(1-frac) + old[⌈i + d/0.10⌉]·frac
```

Without this the map describes where the room used to be. A 7 cm step is most of a cell.

**Decay** ×0.96 per frame, so a room that changes is eventually believed.

**Belief threshold** log-odds > 1.75 ≈ 85% posterior.

**Validated on a desktop JVM against the logged failure:**

| scenario | result |
|---|---|
| phantom on 61% / 91% / 99% of pixels, confidence 10 | **0% false alarms** |
| real wall at 1.1 m, confidence 200 / 120 / 60 | **100% detected** |
| real pillar walked into from 2.6 m | tracked on 30 of 30 frames |
| empty corridor, far walls only | 0 false alarms in 40 frames |

---

## 6. Object memory (ObjectMemory)

The detector runs at 5 Hz, the corridor at 30 Hz. The frame where the corridor decides to speak
usually has no box in it.

Each detection becomes a **track**: label, image position, distance, lateral offset, frame share,
last-seen time, sighting count.

| Rule | Value | Reason |
|---|---|---|
| minimum sightings before naming | 2 | one logged run called a backpack a "toilet" exactly once |
| forget after | 2.5 s | |
| prediction trusted for | 1.5 s | |
| same-object match radius | 0.22 image widths | |
| distance match tolerance | 0.60 m | |

**Motion prediction.** A track seen `t` seconds ago at distance `d0`, with the wearer walking at
`v`, is now at:

```
d(t) = d0 - v·t
```

Match the corridor's current reading against `d(t)`, not against `d0`. Measured matching errors
after this: **0.00 to 0.06 m**.

**Contact override.** When a track fills more than 42% of the frame, it is within arm's reach
whatever depth says. Measured: pressed against a suitcase, the centre of the depth image reported
1.2-2.5 m on 90% of frames and nothing below 0.7 m. Depth-from-motion has no parallax at contact
range; apparent size does not have that problem.

---

## 7. Sensor fusion rules

Three places where two senses disagree, and who wins.

| Conflict | Winner | Why |
|---|---|---|
| depth says clear, model sees an object | **more cautious of the two** | 0% of depth returns closer than 1.2 m with a clear plastic bottle at 1 m |
| depth says 2 m, object fills the frame | **apparent size** | depth cannot measure contact range |
| depth blind, backup vision has an answer | **backup vision** | the screen already says which sense is talking |
| floor stops before free space does | **floor** | a staircase down is free space at body height |
| local detector has no word, depth is certain | **ask the vision model once** | COCO has 80 classes; pillars and desks are not among them |

**Naming escalation.** When the corridor is certain (<2.5 m) and the local detector has no label,
and the wearer is slower than 0.9 m/s, one vision-model call names it. 8 s cooldown, answer kept
6 s. This is the same architecture as the blindness fallback, at the level of a noun.

---

## 8. Arbitration (ModeArbiter)

Decides **who speaks**, not what is out there. From each sense's own confidence, not a fixed
priority list.

| Channel | Owns the output when |
|---|---|
| GUARDIAN | something in the body corridor |
| APERTURE | fan coverage > 60% and nearest barrier 1.2-2.5 m |
| TERRAIN | disabled, see section 11 |
| QUIET | nothing worth saying |

**Overrides, in order:**

1. contact (object fills the frame)
2. imminent collision < 1 m **and** walking **and** the occupancy grid supports it
3. floor runs out
4. blindness with a cause, only after the backup channel has had 5 s to try

---

## 9. Output budget

**Vibration carries state, speech carries change.**

| Channel | Encoding |
|---|---|
| haptics | double buzz, repeat rate rises as distance falls, full amplitude, alarm channel |
| speech | one line per *situation*, not per sentence |
| screen | what the algorithm sees, for a room that cannot feel anything |

**Situation key** = noun + half-metre band + side. Walking a chair down from 4 m to 1 m is **one
situation** however many different sentences describe it. Repeat only after 20 s.

Before this, the rationing compared the *text*, which changed every half metre, so nothing was ever
a repeat and it spoke every 5 seconds forever.

**Two coded parameters maximum.** Frontiers in ICT 2017, doi:10.3389/fict.2017.00023 - a walking
user can hold about two simultaneously-coded parameters. Hence no third haptic channel: bearing
goes to speech, fit stays on the skin.

---

## 10. Known sensor limits, measured

| Limit | Evidence | Handled by |
|---|---|---|
| clear plastic and glass invisible | 0% of centre depth < 1.2 m with a bottle at 1 m | model verdict overrides |
| contact range unmeasurable | 90% of frames reported 1.2-2.5 m while touching | apparent-size override |
| level camera cannot see the floor | 40° vertical FOV; at 1 m it covers ±36 cm about the axis | measured nearest-floor, TILT DOWN |
| textureless surfaces invisible | matte cabinet, backpack | floor-runout, backup vision |
| standing still degrades depth | 61% coverage still vs 95% walking | SWAY prompt |
| depth freezes | one 68 s stall; raw stream healthy throughout | raw failover, then session restart |

**Field of view arithmetic** for a camera at 1.5 m held level:

| distance | heights visible |
|---|---|
| 1 m | 1.14 - 1.86 m |
| 2 m | 0.77 - 2.23 m |
| 3 m | 0.41 - 2.59 m |
| 4 m | 0.04 - 2.96 m |

A 30 cm bottle becomes visible at about 4 m and *disappears as you approach*. The mount must tilt
the phone 25-30° down.

---

## 11. What is switched off, and why

| Feature | Reason |
|---|---|
| TerrainWatch (height-comparison drop detection) | three rewrites; the last announced "step up 11 cm" at the edge of a rug across 107 stable frames. A few hundred pixels at the bottom of a 160x90 image cannot resolve 10 cm. |
| MonoDepth (Depth Anything V2) | relative map fine, least-squares fit onto ARCore metres never rose above r=0. 98 MB for arbitrary numbers. |
| Pointing probe | needs hand pose, image direction, un-rotation and a hitbox match to agree at once |

Replaced by: **floor-runout**, which asks a coarser question the data can answer - does the floor
stop before the free space does.

---

## 12. Measured before and after

| | before | after |
|---|---|---|
| depth unusable (blind or frozen) | 39% of frames | 1 frame in 116 |
| longest depth freeze | 68 s | none |
| free space reported straight ahead | 0.79 m median | 4.00 m median |
| frames claiming an obstacle under 0.6 m | most | 0 of 116 |
| fan coverage while tilted at the floor | 37% median | 100% |
| wearer's own body counted as an obstacle | 48% of frames | suppressed |
| hazard frames reporting distance zero | 14% | 0 |
| object name matching error | no match on 83% of frames | 0.00 - 0.06 m |
| phantom surface believed | 99% of frames | 0% |

---

## 13. Prior work, and what is ours

| Source | What it established |
|---|---|
| Neugebauer 2020, doi:10.1371/journal.pone.0237344 | forehead phone + ARCore depth + sonification **lost to a white cane**: 68.4 s vs 27.0 s, 2.96 vs 0.78 collisions |
| Virtual Whiskers, arXiv:2408.14550 | free-space encoding cut cane contacts 70-80%; obstacle encoding did no better than nothing |
| de Paz 2019, doi:10.1371/journal.pone.0213342 | shoulder-width ratio 1.22 is where blind walkers turn sideways |
| Frontiers in ICT 2017, doi:10.3389/fict.2017.00023 | two coded parameters is the ceiling while walking |
| Moravec & Elfes 1985 | occupancy grids, inverse sensor model, log-odds |

**Ours:** aperture judged against shoulder width on a phone; blindness reported with an attributed
cause; confidence-weighted occupancy evidence used to veto the loudest warning; sensor-trust
arbitration between four channels; naming escalation from an 80-class detector to an
open-vocabulary model only when the cheap one has no word.

---

## 14. Theme: signals that shape our world

The whole project is one signal chain. Light reaches a lens; the wearer cannot receive that
signal, so it is converted into two they can: a rhythm on the skin and a sentence in the ear.
Everything in sections 2 to 9 is what happens between those two ends.

### Four signals, and what each one is worth

| Signal | Carries | Fails at |
|---|---|---|
| depth from motion | distance, everywhere in view | glass, matte black, contact range, standing still |
| ARCore confidence | **how much the depth signal is worth** | nothing: it is the only honest channel |
| colour + detector | what a thing is, 80 known words | small, distant, dim, unknown categories |
| vision model | what a thing is, any word | needs a network, costs money, cannot measure |

No single one is enough. The project is the arbitration between them.

### The idea the project is actually about

**A confident wrong signal is worse than no signal.**

Every comparable system treats a sensor reading as fact. When ARCore's depth stopped working, the
corridor read empty, the device said nothing, and the wearer correctly heard "clear". That is the
most dangerous behaviour a mobility aid can have and it is the common one.

So this device carries a second signal about its first: `ARCore confidence`, per pixel, every
frame. Measured on a real corridor walk, 90% of pixels sat in the lowest confidence band while
depth reported a solid surface at 0.9 m on 99% of frames. **Nothing was there.** The signal was
stable, repeatable, and false.

Three consequences run through the whole design:

1. **Evidence, not readings.** Section 5. A range reading is not a fact about the world, it is one
   vote, weighted by `(confidence/255)²`, accumulated in a log-odds grid that also records what the
   ray proved *empty* on its way. A phantom has to be believed by nearly every frame to survive.
2. **Blindness is a signal too.** When depth fails, that failure is transmitted, with its cause:
   too fast, incoherent, unstable, frozen. Silence is not neutral; silence means clear.
3. **Bandwidth to a human is the scarcest channel in the system.** Two coded parameters is the
   measured ceiling while walking, so the design spends them rather than adding more. Vibration
   carries state, speech carries change, and a repeated sentence is a wasted transmission.

### Why it shapes anything

253 million people live with vision impairment, 36 million are blind. The signal they are missing
is already arriving at every phone camera in their pocket; it is just encoded for eyes. This
re-encodes it for skin and ears, on hardware they already own, offline.

The research this is built on is the reason it is not a repeat: the same phone, the same depth, the
same haptics were published in 2020 and lost to a white cane. Encoding the obstacle field turned
out to be the wrong signal to send. Encoding **free space** cut cane contacts by 70-80%. Choosing
what to transmit mattered more than how well it was measured.

### One line

Turn a signal a blind person cannot receive into two they can, weight every signal by how much it
is worth, and transmit the failure as loudly as the reading.


---

## 15. Algorithms, and what was rejected

Every choice here had an obvious alternative. The alternative is what a judge will ask about.

| Problem | Chosen | Rejected, and why |
|---|---|---|
| what to encode for the wearer | free space (which bearings fit) | **obstacle field** ("something at 1.2 m, ten o'clock"). Published on this exact hardware in 2020 and lost to a white cane. Virtual Whiskers found free-space encoding cut cane contacts 70-80% and obstacle encoding did no better than nothing. |
| measuring vertical | gravity from ARCore pose | **camera-relative height.** Only correct when the phone is level, which it never is on a person. Tilt down to see the floor and the corridor tilts into the floor. |
| corridor bottom edge | anchored to the measured floor | **fixed offset from the camera.** -0.90 m from a forehead camera puts the corridor floor 65 cm above the ground, making bottles and bags structurally invisible rather than missed. |
| floor height estimate | 30th percentile, sorted most-negative first | **60th percentile.** Estimated the floor as wherever the clutter was, which then raised the corridor above the clutter. |
| reported obstacle distance | 5th percentile of corridor hits | **minimum.** The single nearest pixel wandered 0.3 m frame to frame on a static scene and once sat a full metre in front of the real obstacle. |
| deciding a bearing is passable | configuration-space dilation | **measure gap width, compare to shoulder.** Median measured gap in a cluttered room was 0.23 m against a 0.45 m shoulder, so it always said "narrow" and never gave a direction. |
| openness threshold | relative: barrier + 0.60 m | **fixed 2 m.** A doorway at 2.5 m vanished because every direction cleared the bar and the whole fan read as one gap. |
| rejecting phantom obstacles | log-odds occupancy grid, confidence-weighted | **temporal median.** Cannot help when the sensor lies consistently: depth reported a surface at 0.9 m on 99% of consecutive frames with nothing there. Every frame agreed. |
| weighting a depth sample | `(confidence/255)²` | **linear, or unweighted.** Linear still lets a 90%-low-confidence field through. Unweighted is what produced the phantom in the first place. |
| depth source for the grid | raw depth | **smoothed depth.** Fills its own holes by interpolation and reports 100% valid whether it knows or is guessing. It has no confidence plane to weight by. |
| naming an obstacle | track with motion prediction | **per-frame box matching.** The detector runs at 5 Hz and the corridor at 30 Hz, so 5 frames in 6 have no box. Matching failed on 83% of frames. |
| rejecting a bad label | two sightings required | **trust the first.** One logged run called a backpack a "toilet" exactly once, and once is enough when it reaches an ear. |
| distance at contact range | apparent frame share | **depth.** Depth-from-motion has no parallax at contact: pressed against a suitcase, 90% of frames reported 1.2-2.5 m and nothing below 0.7 m. |
| drop detection | floor runs out before free space does | **compare near and far floor band heights.** Three rewrites; the last announced "step up 11 cm" at the edge of a rug across 107 stable frames. A few hundred pixels at the bottom of a 160x90 image cannot resolve 10 cm. |
| naming things outside COCO | one vision-model call, gated | **a bigger detector.** MediaPipe ships Lite0, Lite2 and SSD MobileNetV2 and nothing else; Lite4 returns 404. A larger model has the same 80 classes anyway. Open-vocabulary detectors take seconds per frame on a phone. |
| when to speak | one line per situation | **one line per changed sentence.** The text changes every half metre, so a text-based repeat check never fired and it spoke every 5 seconds forever. |
| which sense speaks | arbitration by each sense's own confidence | **fixed priority list.** A dresser two metres away is an obstacle, a gap either side, and a change in the floor at the same time. All three fired. |
| coordinate mapping image ↔ depth | `frame.transformCoordinates2d` | **hand-rolled projection through intrinsics.** Fired on 1 frame in 72, because the detector works on a bitmap straightened for the current grip and the grip changes as the phone tilts. |
| camera image conversion thread | worker | **render thread.** Killed the depth stream outright once the CPU image went past VGA: raw validity fell from a 61-99% band to a median of zero. |

---

## 16. One frame, end to end

A worked trace. Wearer walking at 0.8 m/s toward two backpacks 2.0 m away with a 0.9 m gap between
them, phone at chest height tilted 25° down.

**1. ARCore delivers** pose, smoothed depth, raw depth, raw confidence.

**2. Gravity.** `camera.getPose().inverse().rotateVector(WORLD_UP)` → `up` in camera coordinates.

**3. Corridor scan**, 14 400 depth pixels. For each:
```
pointX = z(x-cx)/fx ;  pointY = z(cy-y)/fy
aboveCamera = pointX·up₀ + pointY·up₁ - z·up₂
ground      = √(range² - aboveCamera²)
```
- 1 900 points are the wearer's own body (`ground < 0.35`) → cut, counted
- 4 100 points are floor (`aboveCamera < -1.05`, within ±0.60 lateral) → floor samples
- floor height = 30th percentile → **-1.38 m** → corridor bottom = **-1.23 m**
- 380 points land inside the corridor box → 5th percentile → **hazard at 2.04 m, lateral +0.02**
- every slab point also goes to the fan

**4. Fan.** 41 bins. `barrier = 2.02 m`, `need = 2.62 m`. Dilate by 0.30 m: each bag blocks
±8.6° around itself. Bins from -4° to +4° survive with a free distance of 5.5 m (the far wall).
`bestHeading` → **0°**, WALK.

**5. Occupancy grid**, fed from raw depth + confidence. Cells shift back by the 0.027 m travelled
since the last frame. 380 corridor returns at ~2.0 m, confidence ~150 → `w = 0.35` each. Cell 17
(2.05 m) rises to log-odds **+2.6**; cells 0-16 fall. `supportAt(2.04) = 2.6 > 0.8` → the reading
is evidence-backed.

**6. Detector**, running on a 5 Hz worker, last completed 140 ms ago: two boxes, both `backpack`,
depth sampled at their centres → 2.1 m and 2.0 m. `ObjectMemory` matches them to existing tracks
(sightings 6 and 5) and predicts forward by `0.8 × 0.14 = 0.11 m`.

**7. Name.** `nameAt(2.04, +0.02, now, 0.8)` → nearest predicted track at 1.99 m, error 0.05 m →
**"backpack"**.

**8. Arbitration.** No contact (frame share 0.11). Hazard at 2.04 m is not imminent. Floor does not
run out (`floorEnds = 1.95`, `clearAhead = 2.04`, difference 0.09 < 1.00). Channel → **GUARDIAN**.

**9. Output.**
- haptics: double buzz, repeat interval from 2.04 m → ~780 ms
- speech: situation key `hazard:backpack:2.0:straight ahead`. New → speak
  **"Backpack 2.0 metres, straight ahead. Clear to your left."**
- HUD: `BACKPACK · 2.04 m`, direction bar lights AHEAD, pulse bar flashes on the buzz
- CSV row written

**10. Next frame** the wearer has moved 2.7 cm. The grid shifts, the tracks predict, the situation
key is unchanged, so nothing is said again until the noun, the half-metre band or the side changes.
