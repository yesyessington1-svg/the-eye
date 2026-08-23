# What The Eye actually claims, and against what

Written after reading the field rather than guessing at it. Every claim below is either something
we measured on our own device or something published that we can point at.

## The frame that fits, and it isn't "another obstacle detector"

There is a 2026 position paper - *Assistive Agents Need Accessibility Alignment*
(arXiv:2605.13579) - arguing that assistive systems fail their users in a specific way: they "act
on incorrect information without exposing uncertainty", and "confident but wrong answers can mask
hallucinations and leave users unaware of risks". Its prescription is that "uncertainty should
systematically trigger safety-oriented behaviour, including requesting additional context,
proposing safer alternatives, pausing, or declining to act".

That is a description of The Eye's state machine, written by someone else, before we built it.

- **declining to act** -> `BLIND` when the near samples disagree with each other
- **pausing** -> `STALE` when ARCore hands us the same depth frame twice
- **requesting additional context** -> `SWAY`, where the system asks the wearer to move because its
 own sensor needs motion to see

We did not set out to implement a paper. We got here by measuring our sensor and refusing to let it
lie. But it means the design has a name in the literature, and the honest claim is: *we built the
thing that position paper asks for, on hardware that costs nothing extra, and we have the logs.*

## The failure mode we measured has a name too

*The Escalator Problem: Implicit Motion Blindness in AI for Accessibility* (arXiv:2508.07989) makes
the argument that "a system that is 99% accurate on discrete object labels but fails on 100% of
implicit motion tasks is a brittle and untrustworthy system".

Our own bring-up found exactly that shape of failure, from the other direction:

- a hand held still at 45 cm is invisible to depth-from-motion, and smooth depth paints the
 background straight through it while reporting 100% valid pixels
- pointing the phone at a person froze the entire depth stream for up to **60 seconds**, during
 which Guardian would have reported a clear corridor on minute-old data

So the class of obstacle our sensor is blind to is *the class that moves* - which is the class most
likely to move into you. That is why person detection is a second channel in colour rather than a
feature bolted on: it fails differently from the first one.

## Why electronic travel aids get abandoned, and what that means for us

From the O&M literature: "subtle cues from cane tips provide a powerful incentive for skilled blind
pedestrians to eschew electronic travel aids". The cane is not a crude tool people tolerate until
something better arrives. It is high-bandwidth, zero-latency, and never lies.

An aid that competes with the cane loses. The Eye is scoped to what a cane physically cannot reach:

| | Long cane | The Eye |
|---|---|---|
| Ground within a pace | excellent, tactile, exact | ignores it deliberately |
| Head and torso height | nothing at all | Guardian |
| Drops two paces out | one pace, by touch | TerrainWatch |
| A specific direction, on demand | no | pointing probe |
| Knowing when it can't see | always knows | four states, spoken |

The same review notes most systems in the literature are "at prototype stages... quite far from
guiding a person with blindness in real-world situations". So is ours. Saying so is not weakness;
claiming otherwise in front of anyone who works in this field is.

## The three claims we will actually make

1. **We characterised a shipping commodity AR stack for safety use and found it unsafe by default.**
 ARCore's depth on a device with no ToF sensor freezes silently for up to a minute, invents
 surfaces through close moving objects, and reports full confidence throughout. We have the logs.
2. **We built the relevance filter, not another proximity alarm.** The contribution is deciding
 which depth samples are about to matter to a body, in a corridor referenced to gravity rather
 than to the phone.
3. **The system reports its own blindness, and asks the wearer to help fix it.** The `SWAY` cue is
 a closed loop between the sensor's needs and the user's body, and it exists because we measured
 that this sensor's quality is a function of how its wearer moves.

## What we do not claim

- Not evaluated with blind users or O&M instructors. The 2014 stereo virtual cane we build on
 didn't either, and said so.
- Not a cane replacement, and the scoping table above is the reason.
- Finger pointing to a tactile distance readout is prior art (Kim et al. 2014). Ours differs by
 running in image space on a phone with no stereo rig, and by being paired with a passive channel.
