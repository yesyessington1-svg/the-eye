# Build log

Appended as we go. Not cleaned up afterwards - the mess is the point.

<!-- One line per meaningful event: something started working, something broke, a
 decision got made, a feature got cut. Timestamp everything.

 Log the failures in particular. "we lost two hours to X" is the entry that
 convinces a judge the team did the work.

 Format:
 HH:MM what happened

 Shape of a good entry (write your own - do not paste these, they're not yours):
 - something started working
 - something broke, and how long it cost
 - a decision, cross-referenced to DECISIONS.md
 - a feature cut, and why
-->


19:01 cloned arcore-android-sdk, opened samples/hello_ar_java. sync clean, no AGP upgrade
19:14 hello_ar_java running on the S25 Ultra (SM-S938B), depth map rendering
19:21 first instrumented run wasted - tapped the screen to place objects, which moved the phone,
 which is the variable the still-test was measuring
19:26 test 1 (still): 11 s of no depth at all, then depth appears the frame motion hits 12 cm,
 then 42 s stationary with no decay. motion starts depth, it doesn't maintain it
19:3x test 2 (walking): raw coverage 61% -> 95%, high-confidence pixels 1.6% -> 50%. above
 30 cm/s smooth depth drops out entirely for 3 s
19:4x test 3 + 3b: hand at 45 cm invisible, moving or still. smooth depth reports the background
 distance through it at 100% confidence
19:5x test 4 (cardboard at 70 cm, phone at forehead): tracks 0.90 -> 0.64 m cleanly, min and p05
 agree within 2 cm. the demo scenario works
20:0x USB cable pulled out during a walking test. Android Studio silently started a Pixel 2
 emulator and installed there. Samsung Auto Blocker then refused the reconnection until the
 phone was unlocked first
20:1x GuardianCorridor + HapticEngine written and wired in. not yet run on device
20:4x stripped the sample: no pawns, no plane grid, no point cloud, app renamed Reach, HUD sized
 for a projector instead of a one-line snackbar
20:5x broke the build badly - a text replacement anchored on "String message = null;" matched the
 copy in onResume instead of onDrawFrame and deleted four methods. rebuilt them from the
 .orig copy taken before the first edit. keep that copy
21:0x SWAY confirmed working from a cold start on device
21:1x BLIND was firing constantly near walls. 12 noisy near pixels anywhere in frame were enough.
 rewrote it: near samples now have to be numerous AND agree with each other
21:3x portal gesture built one-handed (thumb + index as opposite corners) after realising a
 two-handed gesture means putting the white cane down to ask a question
21:4x on-screen portal rectangle added - before it, no test result was falsifiable
21:5x tried FocusMode.AUTO for sharper close hands, depth immediately went to garbage, reverted
22:0x portal payload changed from a distance to a spoken description. the distance stayed as the
 instant offline answer, but a blind user cannot aim at the precision a distance implies -
 a sentence survives sloppy aim, a centimetre reading doesn't
22:1x dropped the vendor SDK for the description in favour of a hand-written HTTP POST. removed
 desugaring and the META-INF excludes with it - two fewer things to break the Android build
