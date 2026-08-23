# Ce mai desenezi

Ai gata: panoul THE EYE, WORKFLOW, CA INPUT, DEPTH, CONFIDENCE, RGB, POSE.
Mai jos, blocurile care lipsesc, cu textul exact pentru fiecare cutie.

---

## BLOC 8 - CELE PATRU CREIERE

Titlu: `CE FACE CU DATELE: 4 CREIERE`

Patru cutii pe un rand, fiecare cu o intrebare mare deasupra.

```
┌─ GuardianCorridor ────┐  ┌─ ApertureScan ────────┐
│  "e ceva in drum?"     │  │  "pe unde incap?"      │
│                        │  │                        │
│  cutie de marimea ta   │  │  41 felii × 1.5°       │
│  0.60m lata            │  │  umfla obstacolele     │
│  2m in fata            │  │  cu jumate de corp     │
│  fund pe podea + 15cm  │  │                        │
│                        │  │  ce ramane liber       │
│  → cel mai apropiat    │  │  = pe unde incapi      │
│    lucru din cutie     │  │                        │
│  → in ce parte e       │  │  → o DIRECTIE          │
└────────────────────────┘  └────────────────────────┘

┌─ OccupancyBeam ───────┐  ┌─ WorldFan ────────────┐
│  "chiar e acolo?"      │  │  "unde era in CAMERA?" │
│                        │  │                        │
│  37 casute × 10cm      │  │  120 sectoare × 3°     │
│  voturi, nu citiri     │  │  360° in jurul tau     │
│                        │  │                        │
│  ocupat: +0.85 × w     │  │  indexat dupa unghiul  │
│  liber:  −0.30 × w     │  │  din INCAPERE          │
│  (tot ce e inainte)    │  │  nu din poza           │
│                        │  │                        │
│  → dovada, nu parere   │  │  → tine minte ce nu    │
└────────────────────────┘  │    mai e in cadru      │
                            └────────────────────────┘
```

---

## BLOC 9 - GRAVITATIE (deseneaza-l, e cel mai usor de inteles vizual)

Titlu: `DE CE MASURAM FATA DE GRAVITATIE`

Doua desene alaturate, acelasi telefon inclinat in jos spre podea:

**Stanga - GRESIT (fata de telefon):**
telefon inclinat 30°, cutia coridorului inclinata si ea, intrand in podea.
Sub el: `podeaua devine "obstacol"`

**Dreapta - CORECT (fata de gravitatie):**
telefon inclinat 30°, cutia ramane dreapta, verticala.
Sub el: `podeaua ramane podea`

Formula intre ele:
```
aboveCamera = pointX·up₀ + pointY·up₁ − z·up₂
ground      = √(range² − aboveCamera²)
```
`up` vine din camera.getPose(). ARCore stie unde e jos.

---

## BLOC 10 - SPATIUL DE CONFIGURATIE (desenul care castiga)

Titlu: `CUM ALEGE DIRECTIA`

Vedere de sus. Tu = triunghi jos in mijloc. Doua ghiozdane la 2m.

**Pasul 1:** doua patrate negre (ghiozdanele), gol de 90cm intre ele.

**Pasul 2:** in jurul fiecarui ghiozdan, un con gri deschis - zona blocata dupa umflare.
Eticheta pe con: `±8.6° la 2m`

**Pasul 3:** culoarul verde ramas intre conuri, sageata verde prin el.
Eticheta: `pe aici incapi`

Tabel langa desen:
```
obstacol la 0.5m  →  blocheaza ±37°
obstacol la 1.0m  →  blocheaza ±17.5°
obstacol la 2.0m  →  blocheaza ±8.6°
obstacol la 3.0m  →  blocheaza ±5.7°
```
Sub tabel: `θ = arcsin(0.30 / distanta)`
Si: `aproape blocheaza mult, departe blocheaza putin`

---

## BLOC 11 - GRILA DE DOVEZI (al doilea desen care castiga)

Titlu: `DE CE NU CREDEM O SINGURA CITIRE`

O linie orizontala impartita in casute de 10cm, de la 0.3m la 4m.

**Sus:** o raza care pleaca din ochi si loveste la 2.0m.
- casutele de la 0.3 pana la 2.0: colorate ALBASTRU, eticheta `LIBER −0.30`
- casuta de la 2.0: colorata ROSU, eticheta `OCUPAT +0.85`
- dupa 2.0: gri, eticheta `NECUNOSCUT`

**Text mare langa:** `raza a ajuns la 2m, deci tot ce e inainte E GOL`

**Jos - problema reala, cu numerele masurate:**
```
coridor cu geamuri:
  adancimea a raportat perete la 0.9m pe 99% din cadre
  NU ERA NIMIC ACOLO
  90% din pixeli aveau incredere sub 20/255

o medie pe mai multe cadre nu ajuta: toate cadrele erau de acord
```

**Rezultatul, cu bifa/x:**
```
fantoma pe 99% din cadre  →  0 alarme      ✓
perete real, incredere 60  →  100% detectat ✓
```

---

## BLOC 12 - COORDONATE: POZA vs CAMERA

Titlu: `DE CE UNGHIURILE SE MISCAU`

Doua desene, acelasi scaun, tu intorci capul 20°.

**Stanga (gresit):** cap drept, scaun la `+10°`. Cap intors 20°, acelasi scaun la `−10°`.
Sub: `"du-te la stanga" inseamna altceva la fiecare cadru`
Rosu: `salt >15° pe 41% din cadre`

**Dreapta (corect):** busola. Scaunul la `nord-est` in ambele desene.
Sub: `unghiul in incapere nu se misca`
Verde: `si harta tine minte ce nu mai e in cadru`

---

## BLOC 13 - CINE CASTIGA CAND DOI SENZORI SE CONTRAZIC

Titlu: `FUSION: 5 CONFLICTE`

Tabel, trei coloane:

| conflict | castiga | de ce |
|---|---|---|
| adancimea zice liber, modelul vede obiect | **cel mai prudent** | 0% returnari sub 1.2m cu sticla la 1m |
| adancimea zice 2m, obiectul umple cadrul | **dimensiunea aparenta** | fara paralaxa la contact |
| adancimea oarba, viziunea de rezerva raspunde | **viziunea** | ecranul spune care simt vorbeste |
| podeaua se termina inainte de spatiul liber | **podeaua** | scarile in jos sunt spatiu liber |
| detectorul local n-are cuvant | **intreaba modelul, o data** | COCO are 80 clase, stalp nu e printre ele |

Sub tabel, mare: `NICIODATA MEDIA. Unul e gresit si stim care.`

---

## BLOC 14 - IESIREA

Titlu: `CE AJUNGE LA OM`

Trei coloane:

```
VIBRATIE          VOCE              ECRAN
poarta STAREA     poarta SCHIMBAREA pentru cine se uita
                                    
doua bataie       o data per        ce vede algoritmul
mai des = mai     SITUATIE          bara de directie
aproape                             bara care pulseaza
                  substantiv +      pe ritmul motorului
un om invata      banda 0.5m +
3 semnale         partea
in 1 minut
15 niciodata      repeta dupa 20s
```

Sub, ca nota: `2 parametri codificati e plafonul masurat (Frontiers in ICT 2017)`

---

## BLOC 15 - CE NU POATE

Titlu: `LIMITE MASURATE, NU PRESUPUSE`

```
sticla transparenta      0% returnari sub 1.2m cu sticla la 1m
lipit de obiect          90% din cadre ziceau 1.2-2.5m in timp ce atingeai
podeaua, telefon drept   la 1m vede doar ±36cm in jurul axei
suprafete mate           nimic de urmarit intre cadre
stalpi, birouri          nu exista in cele 80 de categorii COCO
```

Desen langa: pana de vedere a camerei, cu o sticla de 30cm pe podea.
```
la 4m  →  o vezi
la 3m  →  o vezi
la 2m  →  DISPARE
la 1m  →  invizibila
```
Text: `de aia suportul trebuie inclinat 25-30° in jos`

---

## BLOC 16 - INAINTE / DUPA

Titlu: `CE AM MASURAT`

```
                                  inainte    dupa
adancime inutilizabila            39%        1 cadru din 116
cea mai lunga inghetare           68s        niciuna
spatiu liber raportat in fata     0.79m      4.00m
corpul purtatorului ca obstacol   48%        suprimat
cadre cu distanta zero            14%        0
potrivirea numelui obiectului     esec 83%   eroare 0-6cm
fantoma crezuta                   99%        0%
salt de directie >15°             41%        0%
```

---

## BLOC 17 - TEMA

Titlu: `SIGNALS THAT SHAPE OUR WORLD`

Lant, stanga la dreapta:
```
LUMINA → lentila → [semnal pe care nu-l poti primi]
                            ↓
                   [ce face aplicatia]
                            ↓
        VIBRATIE + VOCE → [semnale pe care le poti primi]
```

Sub, cele patru semnale si la ce esueaza fiecare:
```
adancime din miscare  →  sticla, negru mat, contact, nemiscat
INCREDERE             →  nimic. e singurul canal onest
culoare + detector    →  mic, departe, intunecat, necunoscut
model de viziune      →  are nevoie de retea, nu poate masura
```

Cutia mare, in centru:
```
UN SEMNAL SIGUR PE EL SI GRESIT
E MAI RAU DECAT NICIUN SEMNAL
```

Trei consecinte:
```
1. dovezi, nu citiri
2. orbirea e si ea un semnal - se transmite, cu cauza
3. banda catre om e cel mai rar canal: 2 parametri
```

Linia de final:
```
Transforma un semnal pe care un nevazator nu-l poate primi in doua pe care le poate,
pondereaza fiecare semnal cu cat valoreaza,
si transmite esecul la fel de tare ca citirea.
```

---

## BLOC 18 - CE AM RESPINS (pentru intrebarile juriului)

Titlu: `DE CE ASA SI NU ALTFEL`

```
codificam spatiu liber      NU campul de obstacole
                            (publicat 2020, a pierdut in fata bastonului)

masuram fata de gravitatie  NU fata de telefon
                            (corect doar cand telefonul e drept)

umflam obstacolele          NU masuram latimea golului
                            (median 0.23m fata de umar de 0.45m)

adunam dovezi               NU mediana pe cadre
                            (inutila cand senzorul minte consistent)

pondere (incredere/255)²    NU liniar
                            (liniar tot lasa sa treaca incredere 10)

urme cu predictie           NU potrivire pe cadru
                            (esua pe 83% din cadre)

o intrebare la model        NU un detector mai mare
                            (Lite4 nu exista, si ar avea aceleasi 80 clase)
```
