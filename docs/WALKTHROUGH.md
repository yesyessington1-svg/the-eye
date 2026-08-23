# How The Eye works

Written for someone who does not write Android code. Every file named here is real.

## The one-sentence version

A phone worn at forehead height turns the shape of the room in front of you into vibration, and it
knows the difference between "nothing is there" and "I can't see".

## Two different pictures, thirty times a second

ARCore gives the app two things from the same camera, and The Eye uses them for different jobs.

**The depth picture** is 160x90 - tiny - and every one of those 14,400 pixels carries a distance in
millimetres. This is the safety sense. Nothing about it involves recognising objects: a hanging sign
and a tree branch are the same thing to it, which is the point, because dangerous objects are not in
any dataset.

**The colour picture** is 640x480, an ordinary camera image. The Eye only looks at it a few times a
second, for two things a distance map cannot do: notice the wearer's hand making a gesture, and
notice a person.

## Following one depth frame - `GuardianCorridor.java`

1. **Unproject.** Each depth pixel plus ARCore's lens numbers gives a real point in space: so many
 centimetres left, so many down, so many ahead.
2. **Throw almost all of it away.** Only points inside a box the size of the space your head and
 torso are about to move through survive - 60 cm wide, from 20 cm above the camera down to 90 cm
 below, from 0.60 m to 2.00 m ahead. The floor sits below that box, which is why the floor never
 triggers an alarm.

 *This step is the whole idea.* Every other system reports the nearest object in view. The Eye
 reports the nearest object that is going to hit you.
3. **Take the 5th percentile, not the nearest point.** On a static scene the single nearest pixel
 wandered between 0.90 m and 1.49 m, and in one frame it sat a full metre in front of the real
 obstacle. Alerting on it means buzzing at ghosts.
4. **Decide, and be honest about not knowing.** CLEAR, HAZARD with a distance, BLIND when something
 is closer than we can vouch for and the readings disagree with each other, STALE when ARCore has
 handed us the same frame for most of a second.

Step 4 exists because of something we watched happen: pointing the phone at a person froze the depth
stream, and the frozen frame said the corridor was clear for ten seconds while the phone moved
around the room. A sensor that fails silently is worse than one that fails loudly.

## The floor - `TerrainWatch.java`

Guardian ignores the ground on purpose. Something else has to watch it, because the thing a white
cane is actually for is finding the kerb you are about to walk off, and WeWALK's own documentation
says its obstacle sensor cannot detect drops.

`TerrainWatch` keeps a rolling record of where the floor has been for the last three seconds and
flags departures from it about two paces ahead. There is no hardcoded body height - the floor
calibrates itself as the wearer walks, so it works for any height and any mounting angle.

## Asking the wearer for help - `MotionBudget.java`

ARCore has no depth sensor on this phone. It infers depth from the way the view shifts as the phone
moves, which means the picture quality depends on how the wearer moves: standing still gives 61%
coverage and almost no confidence; walking gives 95% and half the pixels confident. From cold it
produced nothing at all for eleven seconds until the phone had travelled about 12 cm.

So when The Eye is losing the picture *and* the wearer has gone still, it says so and asks them to
sway. A white cane works because the user sweeps it. Same bargain, different sense. As far as we can
tell nothing else does this, because nothing else has a sensor that needs the user's body to work.

## The colour picture - `VisionDetector.java`

MediaPipe is Google's on-device vision library. It runs two models here, on one worker thread,
against the same converted frame, entirely offline.

**Hand Landmarker** finds 21 points on a hand. The Eye uses two of them - the thumb tip and the index
tip. Held apart like an L for about a second, that is the shutter: it means "tell me what's ahead".
It is a gesture rather than a button because the phone is meant to be strapped to a forehead, where
there is no button to reach.

**Object Detector**, filtered to one class out of eighty: `person`. This is the second sensor, and
it exists because of a measurement. Depth-from-motion is blind to exactly the obstacles that move - 
a hand at 45 cm was invisible, and a person froze the whole stream. When depth says the corridor is
clear and the colour picture says there is a person standing in it, The Eye believes the one that can
see people.

We did not pick person detection because recognising things is impressive. We picked it because we
measured the hole and this fills it.

## Speaking - `SceneDescriber.java`

The shutter gesture sends the current camera frame to a vision model and speaks one short sentence
back through the phone's own text-to-speech.

This is the only part of The Eye that leaves the device, and it is deliberately off the safety path.
Nothing here can stop you walking into anything. If the network is gone it says so out loud, because
silence would read as "nothing there" - the same lie the rest of the system is built to avoid.

## One motor, one voice - `HapticEngine.java`

A phone has a single vibration motor, so exactly one thing can be said at a time. The order is by
what hurts most if missed: the floor disappearing, then a collision, then a person we can only guess
the range of, then a step up, then an admission that we cannot see.

Three rhythms, not fifteen. Guardian is a double tap whose rate climbs as the obstacle nears.
Terrain is long-short-long. Blindness is one long buzz. The 2014 stereo-camera virtual cane encoded
distance as three *frequencies* - 10, 100 and 600 Hz - using dedicated actuators on the finger and
wrist. A phone motor cannot do that, so The Eye encodes in rhythm and rate instead. That is not a
shortcut; it is the adaptation commodity hardware forces.
