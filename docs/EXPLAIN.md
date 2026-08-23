# The Eye, explicat simplu

Fara jargon. Fiecare clasa, ce face, si de ce exista.

---

## 1. Ce face aplicatia, intr-o fraza

Telefonul se uita inainte, isi da seama daca poti merge, si iti spune prin **vibratie** si **voce** -
iar cand nu poate sa-si dea seama, **o spune cu voce tare** in loc sa taca.

Ultima parte e cea importanta. Tacerea inseamna "e liber". Un sistem care tace cand se strica
minte fara sa vrea.

---

## 2. Ce primeste telefonul (partea pe care ai desenat-o deja)

| Ce | Ce e, pe scurt |
|---|---|
| **DEPTH** (adancime) | pentru fiecare punct din imagine, cat de departe e. O imagine mica, 160x90 |
| **CONFIDENCE** (incredere) | pentru fiecare punct, **cat de sigur e telefonul pe distanta aia**. 0 = ghiceste, 255 = stie |
| **RGB** | poza normala, color. Din ea afli *ce* e obiectul, nu cat de departe |
| **POSE** | unde e telefonul in camera si incotro e intors |

**Adancimea vine din miscare.** Telefonul n-are senzor de distanta. Compara doua cadre consecutive
si deduce distanta din cat s-a deplasat imaginea - ca atunci cand misti capul si obiectele apropiate
par sa se miste mai mult decat cele departate.

De aia: **daca stai nemiscat, adancimea se strica.** Nu are ce compara.

### Doua feluri de adancime

| | SMOOTHED (netezita) | RAW (bruta) |
|---|---|---|
| goluri | nu are, le umple singura | are goluri reale |
| onestitate | zice mereu "100% valid" | are harta de incredere |
| o folosim la | geometrie (unde sunt lucrurile) | **dovezi** (cat de mult sa credem) |

Cea netezita **isi inventeaza raspunsurile** unde nu stie, si nu-ti spune unde a inventat. De aia
avem nevoie si de cea bruta.

---

## 3. Fiecare clasa, in cuvinte simple

### GuardianCorridor - "e ceva in drumul meu?"

Isi imagineaza o **cutie de marimea ta** intinsa doi metri in fata. Se uita la toate punctele din
adancime si le pastreaza doar pe cele care cad in cutia aia. Cel mai apropiat lucru din cutie e
raspunsul.

Trei subtilitati:

- **Masoara inaltimea fata de gravitatie, nu fata de telefon.** Daca inclini telefonul, cutia
  ramane dreapta. Fara asta, cand inclini telefonul spre podea, cutia se inclina si ea in podea.
- **Fundul cutiei sta pe podeaua masurata**, nu la o distanta fixa sub cameră. Altfel, cu telefonul
  la frunte, fundul cutiei ajunge la 65cm deasupra solului si o sticla de pe jos e **invizibila
  prin constructie**.
- **Taie punctele care esti tu.** Genunchii tai sunt cel mai apropiat lucru din cutie daca nu-i
  scoti.

### ApertureScan - "pe unde incap?"

Guardian iti zice ce e in drum. Asta iti zice **pe unde s-o iei**.

Imparte ce e in fata in **41 de felii de cate 1.5 grade**, ca un evantai. Pentru fiecare felie
calculeaza cat de departe ai putea merge pe directia aia. Apoi cauta felia cea mai buna.

**Ideea cheie (spatiu de configuratie):** nu masoara cat de lat e golul si dupa aia compara cu
umerii tai. In loc de asta, **umfla fiecare obstacol cu jumatate din latimea ta**. Ce ramane liber
dupa aia e, automat, pe unde incapi.

```
un obstacol la 1 metru blocheaza ±17°
un obstacol la 2 metri blocheaza ±8.6°
```

Aproape blocheaza mult, departe blocheaza putin. Asta e ce face si un robot inainte sa aleaga
directia.

### OccupancyBeam - "chiar e ceva acolo, sau senzorul minte?"

Problema pe care o rezolva: pe un coridor cu geamuri, adancimea a raportat **un perete la 90cm pe
99% din cadre**. Nu era nimic acolo. Minciuna era stabila, deci o medie pe mai multe cadre n-ajuta.

Solutia, luata din robotica: nu crede o citire, **aduna dovezi**.

Imparte drumul din fata in **37 de casute de cate 10cm**. Cand o masuratoare zice "ceva la 2 metri",
casuta aia primeste un vot **"ocupat"** - dar toate casutele **dinainte** primesc vot **"gol"**,
pentru ca raza a trecut prin ele.

Aia e partea pe care o uita toata lumea. Un cadru care citeste 2 metri **sterge activ** fantoma de
la 90cm, nu doar o supravoteaza.

Si fiecare vot e ponderat cu increderea:

```
greutate = (incredere / 255)²
```

**La patrat.** Fantoma statea la incredere 10, deci votul ei valora 0.0015. Un perete real la
incredere 60 valoreaza 0.055 - de patruzeci de ori mai mult, la aceeasi distanta.

Testat: fantoma pe 99% din cadre → **0 alarme**. Perete real → **detectat pe 100%**.

### WorldFan - "unde erau lucrurile in camera, nu in poza"

Doua probleme, o cauza.

Camera vede 65 de grade. Cand recomanda o directie la marginea aia, recomanda ceva ce abia vede -
si o data te-a trimis intr-un scaun.

Si mai rau: **evantaiul masura unghiurile fata de cameră.** Intorci capul 20 de grade, acelasi scaun
se muta din +10 in −10. "Du-te la stanga" insemna altceva la fiecare cadru.

Solutia: o harta a camerei in **120 de sectoare de 3 grade, acoperind 360 de grade**, indexata dupa
unghiul **in incapere**, nu in poza.

- unghiurile nu se mai misca sub tine cand intorci capul
- harta **tine minte** ce ai vazut acum doua secunde, deci stie si ce nu e in cadru acum
- cand faci un pas, fiecare sector se apropie cu cat te-ai apropiat de el

### VisionDetector - "ce e obiectul ala"

Ruleaza doua modele pe poza color:
- unul gaseste **mana** (pentru gestul L)
- altul gaseste **obiecte** si le pune o eticheta

Modelul de obiecte stie 80 de categorii (COCO). Filtram la 33 - alea in care te poti impiedica.
Restul (frisbee, periuta de dinti) sunt erori pe obiecte casnice.

### ObjectMemory - "tine minte ce a vazut"

Detectorul ruleaza de **5 ori pe secunda**, coridorul de **30**. Deci in cadrul in care coridorul
vrea sa vorbeasca, de obicei **nu exista nicio cutie de obiect**.

Fiecare detectie devine o **urma** care tine minte: eticheta, unde era, cat de departe, cand.

Doua reguli:
- **doua vederi inainte sa spuna numele.** O data a zis "toaleta" despre un rucsac. O data e destul
  cand ajunge in urechea cuiva.
- **prezice distanta cu viteza ta.** O urma vazuta la 2.4m acum o secunda, cu tine mergand cu
  0.9 m/s, e prezisa acum la 1.5m. Fara asta compara 1.5 cu 2.4 si nu potriveste nimic.

Plus: **daca un obiect umple peste 42% din cadru, e la un pas de tine** indiferent ce zice
adancimea. Cand esti lipit de o valiza, adancimea vede **prin ea** si zice 2 metri.

### ModeArbiter - "cine vorbeste acum"

Nu decide ce e afara. Decide **cine primeste cuvantul**.

Prima varianta rula toate canalele deodata si era de nefolosit: un dulap la doi metri e simultan
un obstacol, un gol de fiecare parte, si o schimbare de podea. Toate trei vorbeau. Fiecare avea
dreptate. Rezultatul era zgomot.

### SpeechManager - "cat de des are voie sa vorbeasca"

Regula: **vibratia poarta starea, vocea poarta schimbarea.**

Un perete la doi metri vibreaza cat timp e acolo, si e **numit o singura data**.

Bug-ul reparat: comparam **textul**. Textul se schimba la fiecare jumatate de metru, deci nimic nu
era vreodata o repetare si vorbea la fiecare 5 secunde la infinit. Acum compara **situatia**:
substantiv + banda de jumatate de metru + partea. Sa mergi spre un scaun de la 4 metri la 1 metru e
**o singura situatie**.

### HapticEngine - "vibratia"

Un singur tipar: doua bataie scurte, care se repeta **mai des pe masura ce te apropii**. Distanta
e in ritm, nu intr-un cod pe care trebuie sa-l inveti.

Un om invata trei semnale tactile intr-un minut. Cincisprezece nu invata niciodata.

### SceneDescriber - "intreaba modelul de viziune"

Singura parte care iese din telefon. Trei roluri:

1. **SCENE** - faci semnul L sau zici "look", iti descrie ce e in fata si daca poti merge
2. **FALLBACK** - adancimea a orbit, il intrebam noi ce vede
3. **NAME** - coridorul e sigur ca e ceva acolo dar detectorul local n-are cuvant pentru el
   (stalp, birou, calorifer). Il intrebam **o data**, si tinem minte raspunsul

### SessionRecorder - "scrie tot intr-un fisier"

Un rand pe cadru, intr-un CSV. Dupa demo il tragi pe laptop si scriptul face grafice.

Asa am gasit ca 14% din cadrele de pericol raportau distanta zero - invizibil in 780 de linii de
text, evident intr-un grafic.

---

## 4. Un cadru, pas cu pas

Mergi cu 0.8 m/s spre doua ghiozdane la 2 metri, cu un gol de 90cm intre ele.

1. **ARCore da** poza, adancimea, increderea, pozitia telefonului
2. **Gravitatia:** afli incotro e "jos", indiferent cum tii telefonul
3. **Coridorul** trece prin toate cele 14.400 de puncte de adancime:
   - 1.900 sunt corpul tau → aruncate
   - 4.100 sunt podeaua → din ele afli inaltimea podelei
   - 380 cad in cutia din fata ta → **cel mai apropiat: 2.04 metri**
4. **Evantaiul** umfla fiecare ghiozdan cu jumatate de corp. Feliile de la −4° la +4° raman libere
   → **mergi drept**
5. **Grila de dovezi** confirma: casuta de la 2.05m are dovezi puternice, deci pericolul e real
6. **Detectorul** a vazut doua cutii "backpack" acum 140ms, la 2.1 si 2.0 metri
7. **Memoria** prezice: 2.1 − 0.8×0.14 = **1.99m**. Coridorul zice 2.04. Eroare 5cm → **e acelasi
   obiect** → "backpack"
8. **Arbitrul:** nu e contact, nu e sub un metru, podeaua nu se termina → canalul e **Guardian**
9. **Iesirea:**
   - vibratie la fiecare 780ms
   - voce: **"Go left. Backpack, 2 metres."**
   - ecran: `BACKPACK · 2.04 m`
   - un rand in CSV
10. **Cadrul urmator** te-ai miscat 2.7cm. Situatia e aceeasi, deci **nu se mai spune nimic** pana
    cand se schimba substantivul, banda de distanta sau partea.

---

## 5. Cele trei idei grele, cu analogii

**Grila de dovezi.** Ca un martor care nu te crede din prima. Ii spui "e o masina rosie acolo" - nu
scrie asta in dosar, adauga un punct la ipoteza aia. Daca zece oameni spun acelasi lucru, devine
fapt. Si daca cineva zice "am trecut pe acolo si nu era nimic", **scade** punctele, nu doar adauga
in alta parte.

**Spatiul de configuratie.** Cand parchezi, nu masori latimea locului si dupa o compari cu masina.
Te uiti unde incape masina. Umfli obstacolele cu jumatate din latimea ta si te uiti ce ramane.

**Coordonate in camera vs in poza.** Daca iti zic "e o usa in dreapta ta" si tu te intorci, usa nu
mai e in dreapta. Daca iti zic "e o usa spre nord", ramane spre nord orice ai face. A doua varianta
e singura pe care o poti tine minte cand te misti.

---

## 6. Ce nu poate si de ce

| Nu vede | De ce | Ce facem |
|---|---|---|
| sticla transparenta | lumina trece prin ea, n-are textura de urmarit | modelul de viziune o vede, si cel mai prudent castiga |
| ce e lipit de tine | fara paralaxa, adancimea vede **prin** obiect | daca umple 42% din cadru, e la un pas |
| podeaua, cu telefonul drept | camera vede ±36cm la un metru; un dulap de 90cm e sub banda aia | scrie `TILT DOWN` pe ecran |
| suprafete mate uniforme | nimic de urmarit intre cadre | podeaua care se termina + viziune de rezerva |
| stalpi, birouri | nu exista in cele 80 de categorii | intrebam modelul de viziune, o data |

Ultimul rand din tabel e important pentru intrebarea "de ce nu punem un model mai mare": **nu
exista.** MediaPipe livreaza trei detectoare si il folosim pe cel mai mare. Iar unul mai mare ar
avea **aceleasi 80 de categorii**.
