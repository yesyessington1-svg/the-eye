# The Eye - masuratori de bring-up (ARCore depth pe Galaxy S25 Ultra)

Device: Samsung SM-S938B (S25 Ultra), Android 16 / API 36, **fara senzor ToF**.
ARCore: Google Play Services for AR 1.55. Sample: hello_ar_java, ARCore client 1.54.
Toate cifrele vin din logcat (`EYE_DEPTH`), nu din impresii vizuale.

## Fapte stabilite

- **Harta de adancime: 160x90.** Imaginea camerei e ~4000x2250, deci 1 pixel de
 adancime ~= 25x25 pixeli de camera. Necesar la maparea landmark-urilor MediaPipe.
- **Smooth depth (`acquireDepthImage16Bits`) raporteaza mereu valid=100%.**
 Nu lasa goluri: interpoleaza si umple tot. Consecinta: **nu poate semnala unde ghiceste.**
 Acolo unde nu stie, livreaza un numar cu aceeasi aparenta incredere ca acolo unde stie.
- **Exista pierderi totale de cadru** (valid=0% pe tot cadrul), ~7% din prima rulare.
 Deci orbirea exista, dar e binara la nivel de cadru, nu graduala la nivel de pixel.

## Rulare 0 - INVALIDA ca test 1

39 de cadre. valid=100% pe toate cele cu date. `nearest` in centru a oscilat
0.45m -> 1.61m. **Test invalid**: operatorul a atins ecranul ca sa plaseze obiecte,
deci telefonul s-a miscat. Exact variabila testata.
Lectie de metoda: fara o masura a miscarii nu putem distinge testul 1 de testul 2.

## Consecinte asupra design-ului

1. Semnalul "nu vad" nu se poate construi pe smooth depth. Singura sursa care
 suporta onestitate e raw depth + imaginea de confidenta. De masurat inainte de a
 decide arhitectura.
2. `min` pe un pixel e un declansator prost pentru o alerta de siguranta: un singur
 pixel zgomotos din ~50k aprinde Guardian-ul. De comparat cu percentila 5.

## Test 1 - NEMISCAT - VALID

54 de cadre la 1 Hz. Telefon indreptat spre mobilier de lemn, ecranul neatins.

**Rezultat principal: miscarea e necesara ca sa PORNEASCA depth-ul, nu ca sa-l mentina.**

- 19:26:22 -> 19:26:32: **11 secunde fara niciun depth** (smooth 0%, raw 0%), timp in
 care telefonul s-a miscat 0.1-2.5 cm/s. Nu e suficient ca sa initializeze.
- 19:26:33: primul cadru cu depth apare exact la `moved=12.1cm`.
- 19:26:35 -> 19:27:16: **42 de secunde stationar** (medie 0.25 cm/s, 40 de cadre):
 raw valid 57-65%, medie 61.1%, **fara nicio tendinta de scadere**. Smooth 100% constant.

Consecinta pentru demo: **plimbare de incalzire de 3 pasi inainte de a sta pe loc.**
Dupa initializare, statul pe loc nu degradeaza nimic in ~45s. Nu e o scuza, e o masuratoare.

### Zgomot temporal (scena statica, telefon static)

| Metrica | medie | interval | sigma |
|---|---|---|---|
| `min` (cel mai apropiat pixel din centru) | 1.15 m | 0.90 - 1.49 m | 0.171 m |
| `p05` (percentila 5) | 1.45 m | 1.09 - 1.59 m | 0.109 m |

`min` e cu 30 cm mai apropiat si cu 60% mai zgomotos decat `p05`. **Guardian nu poate
declansa pe `min`**: ar alarma cu 30 cm prea devreme si ar palpai la scena nemiscata.
Decizie: prag pe percentila + histerezis temporal.

### Confidenta

Doar **1.6%** din pixelii raw valizi trec de 128/255. Prag prost sau confidenta
sistematic mica la depth-from-motion -- nedecidabil dintr-un singur prag.
Instrumentarea a fost schimbata pe histograma completa pe 5 cupe.

## De completat
- [ ] Test 2: 2-3 pasi
- [ ] Test 3: mana in cadru la 40-50cm, in miscare
- [ ] Test 4: telefon la inaltimea capului, carton la ~70cm

## Observatie suplimentara - "nemiscat DUPA mers" (15 cadre, dupa reconectare USB)

Telefon stationar (0-5 cm/s), scena statica la ~1.82m, dar de data asta ARCore avea deja
istoric de miscare acumulat din testul 2.

| Conditie | coverage raw | pixeli conf 80-100% |
|---|---|---|
| Nemiscat dupa doar bootstrap (test 1) | 61% | 1.6% |
| **Nemiscat dupa mers real** | **89-98%** | **9-44%** |

Plimbarea de incalzire nu doar porneste depth-ul: lasa in urma o harta semnificativ mai
buna, care persista cat stai pe loc. Mitigarea pentru demo devine procedura justificata,
nu carpeala.

## Incident de infrastructura

Telefonul s-a deconectat in timpul testului cu mers -- cablul USB-C smuls. Android Studio
a pornit automat un emulator Pixel 2 API 28 si a instalat aplicatia acolo (inutil: fara
camera reala, fara ARCore depth). Reconectarea a fost blocata de Samsung Auto Blocker,
care refuza conexiuni USB noi cat timp telefonul e blocat.

Lectii: (1) testele care implica mers necesita depanare wireless, nu cablu;
(2) de verificat mereu ca tinta `Run` e telefonul, nu un emulator apărut singur.

## Test 3 + 3b - MANA la ~45cm - ESEC (asteptat)

Mana in miscare (12 cadre) si mana nemiscata (16 cadre), ambele la ~45cm in centru.
`min` nu a coborat niciodata sub 1.07m; `p05` a ramas ancorat la distanta fundalului
(~1.6-1.95m). Smooth depth raporta `valid=100%` tot timpul.

**Smooth depth vopseste fundalul peste mana si raporteaza "liber la 1.9m" acolo unde
exista un obstacol la 45cm.** Fals negativ, livrat cu incredere maxima. Nu e o gaura
in date, e o afirmatie gresita.

Ipoteza "miscare independenta" respinsa: mana nemiscata e la fel de invizibila.

**Feature 2 (proba cu degetul) NU e afectata**: MediaPipe gaseste degetul in RGB, iar
depth se citeste de-a lungul directiei, spre tinta de la 1-3m. Mana nu are nevoie de
adancime proprie.

## Test 4 - CARTON la ~70cm, telefon la inaltimea fruntii - TRECE

43 de cadre. **Acesta este scenariul de demo.**

| Metrica | valoare |
|---|---|
| centre min | 0.64 - 0.90 m |
| centre p05 | 0.66 - 0.91 m |
| frame min | 0.60 - 0.82 m |
| ecart p05 - min | **1-2 cm** (vs 30 cm pe scena goala) |
| histograma centru | 100% in banda 0.7-1.2m, apoi pana la 28% sub 0.7m |

- Distanta a scazut monoton 0.90 -> 0.64 m pe parcursul logului. Urmarire curata.
- Un obstacol real, mare si plat, face `min` si `p05` sa coincida: Guardian e mai stabil
 pe obstacole decat pe camera goala.
- Primele 9 cadre: raw depth 0% dar smooth depth corect la 0.80m. Raw are nevoie de
 reinitializare separata dupa repornirea aplicatiei; smooth persista.

## SPECIFICATIE derivata pentru Guardian

- **Interval de incredere: ~0.6 m -> 5 m.** Sub 0.6 m sistemul declara orbire, nu "liber".
- **Prag pe percentila 5**, nu pe minim.
- **Histerezis temporal** obligatoriu: pe scena statica `min` oscila +/- 0.3 m.
- **Plimbare de incalzire** inainte de demo: 11 s de orbire la pornire fara ea.
- Granita exacta a zonei moarte (0.45-0.60 m) ramane nemasurata; nu se poate separa
 "prea aproape" de "tinta proasta" din testele 3b si 4. De masurat cu overlay-ul de debug.

## Aperture scan - synthetic validation 

`ApertureScan` has no Android dependencies, so it compiles and runs on a desktop JVM. Every
threshold below was set from these runs rather than from a device build-test loop - the same
discipline as the Guardian constants, applied before burning a build.

Scenes are flat walls with a single opening, sampled at 1cm laterally across three heights inside
the body slab, with the beam carrying 3.5m past the opening. Six frames per scene, so the
five-frame median and three-frame debounce are both exercised.

| scene | true width | reported | verdict | error |
|---|---|---|---|---|
| open room, walls at 7m | - | - | WALK "clear ahead" | - |
| no depth at all | - | - | UNKNOWN "can't read the space" | - |
| door 0.80m @ 2.5m centred | 0.80 | 0.77 | WALK | −4% |
| door 0.80m @ 1.5m centred | 0.80 | 0.81 | WALK | +1% |
| door 0.90m @ 2.5m, 0.6m left | 0.90 | 0.83 | WALK, −12.8° | −8% |
| door 0.70m @ 2.0m, 0.5m right | 0.70 | 0.61 | WALK, +13.5° | −13% |
| door 0.62m @ 2.0m centred | 0.62 | 0.61 | WALK | −2% |
| door 0.55m @ 2.0m centred | 0.55 | 0.50 | SQUEEZE | −9% |
| door 0.45m @ 1.5m centred | 0.45 | 0.46 | SQUEEZE | +2% |
| door 0.30m @ 2.0m centred | 0.30 | - | BLOCKED | - |
| solid wall @ 1.5m | 0 | - | BLOCKED | - |
| solid wall @ 3.5m | - | - | WALK "clear ahead" | - |

Worst case −13%, and the bias runs conservative: gaps are under-reported more often than over, so
the failure mode is telling a wearer to turn their shoulders when they did not need to.

Three bugs the synthetic scenes caught that a device test would have blamed on the sensor:

1. **A fixed 2m "open" threshold made every doorway past 2m invisible.** Every direction cleared
 the bar, the whole fan read as one 2.4m gap, and the door was gone. Fixed by making openness
 relative to the nearest barrier in the fan (`OPEN_MARGIN_M` above `barrier`) - the question is
 "does this direction get me past the thing in my way", which is relative by construction.
2. **Points beyond the scan range were not counted towards coverage,** so a large room - where
 every surface is far away - reported 0% coverage and the device announced it was blind. Range
 and evidence-of-sensing are different questions.
3. **Bin quantisation ate one bin at each edge of every gap,** because a bin closes if any wall
 pixel falls anywhere inside it. A 0.55m door came back as 0.44m and was called impassable.
 Fixed by narrowing bins from 2.5° to 1.5° and crediting half a bin at each end, which is where
 the true edge sits on average.

Reproduce: `Sim.java` in the scratchpad, `javac ApertureScan.java Sim.java && java Sim`.

## Rulare pe dispozitiv: descoperirea auto-ocluziei

Four builds in forty minutes, each one measured from logcat rather than judged by feel. Logs
archived at `docs/logcat--selfcut.txt`.

### What the numbers did

| | before | after |
|---|---|---|
| depth unusable (BLIND + STALE) | 39% of frames | **1 frame in 116** |
| longest continuous depth freeze | 68 s | none observed |
| free space reported straight ahead (median) | 0.79 m | **4.00 m** |
| frames claiming an obstacle under 0.6 m | most | **0 of 116** |
| floor samples per frame (median) | ~400 | 446, peak 1070 |
| HAZARD distances | - | median 1.33 m, range 0.25-1.55 m |

### 1. The wearer is the obstacle

The depth camera has roughly 20 degrees of vertical field. The floor two metres ahead sits about
40 degrees below the optical axis, so seeing floor-level obstacles *requires* tilting the phone
down - and at that angle the wearer's own legs and feet fill the bottom of the frame.

Measured: on **56 of 116 frames the wearer's own body contributed depth points, median 1172 points
per frame, peak 1885**. Nearly half the frames, and on those frames the body was the nearest thing
in the corridor. That is the whole explanation for "it vibrates way too much" and for a fan that
reported 0.36-0.48 m of clear space in an empty room.

Fixed by cutting a wedge - closer than 0.70 m in ground distance *and* more than 0.55 m below the
camera - out of the corridor and the fan. Nothing a wearer is about to walk into is simultaneously
that close and that low. It costs obstacles inside 70cm, which are obstacles already in contact.

### 2. A dead sensor next to a working one

The smoothed depth stream froze for 68 consecutive seconds while the wearer walked at 40 cm/s -
identical values to the centimetre. The recovery path (disable/re-enable DepthMode) fired **14
times and never unstuck it**. On the same log lines the raw stream's confidence histogram changed
every frame: `84/4/1/2/7%` then `85/7/2/2/2%`.

One sensor was dead, the other was fine, and we were reading the dead one. Guardian now fails over
to `acquireRawDepthImage16Bits()` filtered by confidence when the smoothed image stops being new.
STALE went to zero in the following run.

### 3. Screen-space reasoning does not survive rotation

The first attempt at rejecting the wearer's own foot tested box geometry: bottom edge on the frame
edge, top edge below the middle. It **fired zero times in 119 frames** while the overlay drew a
tidy "person" box around a sock, because it had to guess which edge of a rotated, cropped preview
was down, and guessed wrong.

Replaced with a depth test: when the corridor's self-wedge collects more than 40 points, the body
is in shot and every person box is the wearer. Gravity-referenced depth has nothing to guess.
On the run above it engaged on exactly the 56 frames where the body was visible.

### 4. TerrainWatch: cut after three rewrites

On flat parquet with a rug, the third version logged deviations of −76 cm, −19 cm on 33 frames and
+11 cm on 34, and announced `STEP UP 11 cm` at a rug edge while holding **736 samples across 107
stable frames**. It passed every confidence gate we wrote because it read consistently - and was
consistently wrong.

The strip is a few hundred pixels at the bottom of a 160×90 image at the far end of the depth
range. "Has the ground changed height by ten centimetres" is a finer question than that data can
answer. Disabled, with the measurements recorded in the source as the reason.

### 5. Detector swap

EfficientDet-Lite0 → Lite2 (MediaPipe Tasks, drop-in, ~26% → ~36% COCO mAP). Lite0 had emitted a
person box on 17 of 17 frames where a hand was in view and had labelled a water bottle a person.

### Still open

- **Fan coverage collapses when the phone is tilted down**: median 37%, below the 60% trust
 threshold on 74 of 116 frames, so the aperture reported UNKNOWN on 59. This is honest rather than
 wrong - tilted at the floor, most of the frame is floor, which is excluded from the body slab -
 but it means aperture is a walking-forward channel and Guardian is the looking-down channel.
 AUTO already arbitrates exactly that way; it has not been confirmed on a walk.
- One BLIND frame, cause UNSTABLE. Not worth chasing at this rate.
