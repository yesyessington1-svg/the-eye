# Decisions

Why we built it this way. One entry per real fork in the road - if there wasn't a
plausible alternative, it doesn't go here.

## Stack at a glance

<!-- one line each, no justification needed for the boring ones -->
- Language / runtime: Java on Android, minSdk 24, compileSdk 36
- Frontend: the ARCore `hello_ar_java` sample, cut down. no UI framework
- Backend: none. nothing leaves the phone
- Storage: none
- External APIs: ARCore Depth API (Google Play Services for AR 1.55)
- Deploy: sideload via Android Studio onto a Galaxy S25 Ultra

---

## <decision title - name the thing, not the category>

**What we did.**

**What else we considered.** <the real alternatives, and why each lost>

**Why this won.** <include the constraint: time, API access, what the team already knew>

**What it costs us.** <where this breaks, what we gave up>

<!--
Copy the block above for each decision. Keep 6-12 total.
Order them by how likely a judge is to ask.

Reminder: never write a reason that didn't happen. "We had four hours and two of us
knew this framework" is a legitimate engineering constraint. A benchmark nobody ran
is not.
-->

## We measured ARCore's depth before designing against it

**What we did.** Before writing any The Eye code we instrumented Google's sample to dump depth
image size, valid-pixel coverage, raw-depth confidence and device motion to logcat at 1 Hz, then
ran four scripted tests. Results in `REACH-MEASUREMENTS.md`.

**What else we considered.** Building Guardian straight away against the documented behaviour.
The docs say depth-from-motion needs motion and that useful range starts around 0.5 m, which
sounds like enough to design from.

**Why this won.** The S25 Ultra has no ToF sensor, so every number we needed was device- and
condition-specific. Two of our four results contradicted what we expected: motion turned out to
be needed to *start* depth rather than to maintain it, and a hand at 45 cm was invisible while a
cardboard sheet at 70 cm tracked to within 2 cm.

**What it costs us.** About two hours before the first line of product code, and the
instrumentation is still compiled into the app.

## Guardian alerts on the 5th percentile, not the nearest pixel

**What we did.** `GuardianCorridor` collects every depth sample inside the corridor, sorts them
and alerts on the 5th percentile.

**What else we considered.** The nearest sample in the corridor, which is the obvious reading of
"how far is the closest obstacle".

**Why this won.** On a static scene with the phone held still, the single nearest pixel wandered
between 0.90 m and 1.49 m and in one frame sat at 0.81 m while the 5th percentile said 1.78 m -
a metre of disagreement on the same frame. Alerting on the minimum means buzzing at phantoms. On
a real obstacle the two agreed to within 2 cm, so the percentile costs nothing where it matters.

**What it costs us.** A genuinely thin obstacle covering under about 5% of the corridor - a wire,
a chair leg edge-on - reads as further away than it is, or not at all.

## The corridor is a metric box, not an image-space rectangle

**What we did.** Every depth pixel is projected to camera-space X/Y/Z using the intrinsics ARCore
already exposes via `getImageIntrinsics()`, then tested against a fixed box: +/-0.30 m wide,
+0.20 m to -0.90 m vertically, 0.60 m to 2.00 m deep.

**What else we considered.** Testing a fixed rectangle of depth pixels instead, which needs no
intrinsics at all and was the original plan.

**Why this won.** An image-space rectangle is a cone in the real world - it narrows to about
20 cm wide at 0.7 m and widens past shoulder width at 2 m, so it is too narrow exactly where
collisions are imminent. The relevance filter is the thing we are claiming as our contribution,
and a cone is a weaker version of it. Using a getter is not the intrinsics *reconstruction* work
we cut from scope; it is six lines.

**What it costs us.** The corridor is fixed to one body size and assumes the phone is level at
forehead height. Tilt the head and the box tilts with it.

## Silence means clear, so "blind" gets its own signal

**What we did.** `GuardianCorridor.State.BLIND` fires when samples land in the corridor but nearer
than 0.60 m, and `HapticEngine` answers it with a long single buzz distinct from the Guardian
double-buzz.

**What else we considered.** Sticking to the three patterns in the plan and letting the near dead
zone be silent, or reporting it on screen only.

**Why this won.** Our hand test showed ARCore does not go quiet when it cannot see - smooth depth
reported 100% valid pixels and painted the background distance straight through a palm at 45 cm.
A confident wrong number is worse than no number, and if the dead zone is silent then silence
means both "clear" and "sensor gave up".

**What it costs us.** A fourth pattern in a design that argued for three, and one more thing for
a user to learn. It is behind `BLIND_SIGNAL_ENABLED` in `HapticEngine.java` if we lose that
argument.

## "Too close" only means blind when the near samples disagree with each other

**What we did.** Depth samples inside the corridor but below 0.60 m are collected rather than
discarded. If there are at least 12 of them and they agree to within 25 cm, Guardian reports
HAZARD at their 5th percentile - no minimum size, because small things are exactly what a cane
misses. Only when near samples are both numerous (15% of the corridor) and scattered does it
report BLIND.

**What else we considered.** The first version treated any 12 samples under 0.60 m as blindness,
because 0.60 m is where our bring-up tests stopped being able to vouch for ARCore.

**Why this won.** That version fired constantly, and our first reading of why was wrong. We
assumed the near samples were noise; the person holding the phone pointed out they were the edge
of a laptop and a sofa. So Guardian was seeing real obstacles at 40 cm and answering "I don't
know" instead of "0.40 m". The logs back that up - 100% of samples in the 0.4-0.7 m band, min and
p05 two centimetres apart, which is a surface, not noise. The hand that defeated depth at 45 cm
was small, curved and textureless: a bad target, not proof that everything that close is
unreadable.

An earlier version of this fix also required near samples to fill 15% of the corridor before
reporting them, which would have thrown away the laptop edge that prompted the fix.

**What it costs us.** We now report distances below the range our own tests verified, on the
strength of internal agreement rather than measured accuracy. A large flat surface that depth
reads *consistently wrong* would be reported confidently. Untested and worth saying out loud.

## Fixed focus, even though it costs us sharp close-up hands

**What we did.** `config.setFocusMode(Config.FocusMode.FIXED)`.

**What else we considered.** AUTO, so the portal gesture would see a sharp hand at 30-40 cm
instead of a blur.

**Why this won.** We tried AUTO on device and depth fell apart - readings jumping 2 m, then 13 m,
then nonsense, frame to frame. Every focus hunt physically moves the lens, and depth-from-motion
reads lens movement as the world moving. Depth is the product; hand sharpness is an input to one
feature.

**What it costs us.** Hands closer than roughly 30 cm arrive soft, so the gesture has to work at
arm's length rather than right in front of the lens.
