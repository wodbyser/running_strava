# Phase 1 Audit Report: Running AI Coach

- **Date:** 2026-09-25
- **Scope:** full correctness/trust audit. No code changed in Phase 1.

## Evidence sources

- Code review of `core/` and `runner/`.
- The running app on `localhost:8080` (live HTML output).
- The real PostgreSQL data in `running-db`: 573 activities, of which 520 are runs; 400 runs have multiple laps.
- A throwaway adversarial/sensitivity probe test, run in a copy of the repo (`/tmp/opencode/audit`), against synthetic laps and the real lap export.

## Certainty labels

VERIFIED · PROBABLY CORRECT · NEEDS VALIDATION · UNCERTAIN · INCORRECT

---

## 0. Corrections to the stated context

- **Tests.** AGENTS.md says "No tests exist". That is wrong: `core/src/test/.../WorkoutStructureTest.kt` exists.
- **Best efforts are never stored.** Strava `bestEfforts` are parsed in `StravaApiClientImpl.parseBestEfforts`, but they are never persisted: `ActivityRepositoryImpl.toDomain` sets `bestEfforts = null`.
  - As a result, the "PR-segment" anchors in `CoachService.bestEffortAndIntervalAnchors` never run.
  - `coach.html:176` ("Beste schatting op basis van PR's, beste inspanningen…") is therefore false. (VERIFIED)
- **Repository state.** The branch is 1 commit ahead of `origin/main` and has staged, uncommitted changes. They were left untouched.

---

## 1. Pipeline and units

| Step | Where | Units / notes |
|---|---|---|
| Strava API | `StravaApiClientImpl` | distance m, time s, speed m/s, cadence **per foot** (rpm), `start_date` **UTC**, `timezone` string e.g. `(GMT+01:00) Europe/Brussels`. `start_date_local` is not read. |
| Persistence | `ActivityEntity`, `LapEntity`, `ActivityStreamEntity` | Float columns. `start_date` is `TIMESTAMP WITH TIME ZONE` and is **read back as UTC** (export shows `...Z`). Streams are stored as JSON text. Best efforts and splits are **not stored**. |
| Domain | `Activity`, `Lap`, `ActivityStream` | Same units as Strava. |
| Calculation | `FrontendController`, `CoachService`, `PeriodComparisonService`, `LapClassifier`, `WorkoutStructure`, `AiAnalysisService` | See the inventory below. |
| Display | Thymeleaf templates, Chart.js | Cadence labelled "spm" while the value is per foot. Times shown are UTC. |
| AI prompts | `AiAnalysisService` | Cadence labelled "rpm". HR and power printed as raw Floats. |

---

## 2. Inventory

### 2.1 Calculations (class B/C)

| Calculation | Location | Formula | Status |
|---|---|---|---|
| Pace | `FrontendController.calculatePace`, `AiAnalysisService.calculatePace`, `CoachService.formatPace`, `PeriodComparisonService.formatPaceFromSpeed` | `1000/v` s/km, **truncated** | VERIFIED WITH LIMITATIONS |
| Pace | `WorkoutStructure.pace` | `1000/v` s/km, **rounded** | inconsistent with the others |
| Max pace | `FrontendController.kt:242` | `calculatePace(maxSpeed*3.6)` | **INCORRECT** |
| Dashboard avg pace / HR / cadence | `FrontendController.kt:64-66` | unweighted mean of per-activity values | INCORRECT as "gemiddelde" |
| Weekly km | `FrontendController.kt:98-108`, `AiAnalysisService.kt:294` | sum per Monday-week of the UTC date | VERIFIED WITH LIMITATIONS (timezone; the AI version skips empty weeks) |
| Monthly EF trend | `PeriodComparisonService.buildMonthlyTrend` | `mean(v)/mean(HR)` per UTC month | INCORRECT (mixed populations) |
| PBs | `FrontendController.calculatePBs` | whole activity within ±5% of the distance, min moving-time/distance | **INCORRECT** |
| %HRmax zones | `CoachService.calculatePctMaxHrZones` | 50/60/70/80/90/100 % | VERIFIED WITH LIMITATIONS |
| Karvonen zones | `CoachService.calculateKarvonenZones` | `RHR + p·(HRmax−RHR)` | VERIFIED WITH LIMITATIONS |
| Riegel | `CoachService.predictFromAnchors` | `T2 = T1·(D2/D1)^1.06` | formula correct; anchor choice questionable |
| Efficiency factor | `PeriodComparisonService.buildStats` | `mean(speed)/mean(HR)` | INCORRECT (mixed populations) |
| 150 bpm reference pace | `refHrPaceComparison` | `EF·150` | SCIENTIFICALLY QUESTIONABLE |
| Block avg HR | `WorkoutStructure.Block.avgHr` | time-weighted | VERIFIED |

### 2.2 Heuristics and magic numbers (class D)

| Constant | Location | Justification |
|---|---|---|
| `MIN_SPEED_RATIO = 1.15` | `LapClassifier` | none: UNVALIDATED |
| Noise cutoff: 10 s / 45 m | `LapClassifier` | none: UNVALIDATED (plausible) |
| Same-size tolerance 10% | `WorkoutStructure` | none: UNVALIDATED |
| Recovery-duration tolerance 15% | `WorkoutStructure` | none: UNVALIDATED |
| Max HR = p95 of per-run max HR over the last 12 months (plain max if <5 runs) | `CoachService:79-84` | reasonable outlier filter; UNVALIDATED |
| Resting HR = p0.5 of in-run HR (last 20 runs), clamped to 40–85 | `CoachService.estimateRestingHr` | no basis; real data gives 96 → 85 |
| Prediction anchor window 0.4–2.5× | `predictFromAnchors` | none |
| Rep anchor minimum 150 m / 30 s | `CoachService` | none |
| "Hard effort" anchor: race, OR avg HR ≥ 85% HRmax, OR top 15% speed | `hardEffortWholeRunAnchors` | none |
| Recent form window: 6 weeks (fallback: last 5 runs) | `calculateRecentFormPredictions` | none |
| Terrain: <5 m/km "Vlak", <15 "Heuvelachtig", else "Bergachtig" | `buildRunnerProfile` | none |
| Classification: 15/30/50/70 km/week → Beginner … "Elite" | `buildRunnerProfile` | none; "Elite" by volume is wrong |
| Profile type priority (Berggeit ≥15 m/km, Intervaltrainer ≥0.4/wk, Snelheidsduivel >4.5 m/s, Tempoloper >3.5 m/s, Uithoudingsatleet ≥40 km, Frequente loper ≥5/wk) | `buildRunnerProfile` | none |
| Verdict threshold ±3% | `buildVerdict` | none; no sample-size check |
| Reference HR 150 bpm | `refHrPaceComparison` | none |
| Compare defaults: A = 8–6 months ago, B = last 2 months | `FrontendController.compare` | UX choice |

### 2.3 Labels and classifications

- Per-lap labels: "interval", "float/matig", "rust/herstel", "ruis".
- Workout structure texts: "warming-up", "setpauze", "herstel", "cooling-down".
- Runner profile types, classification, and terrain.
- Verdict texts: "verbeterd", "gedaald", "stabiel".
- "Karvonen (HR-reserve — nauwkeuriger)".
- Zone descriptions, e.g. "10km tot 5km tempo" for 80–90% HRmax.

### 2.4 Charts

| Chart | Page | Axes / aggregation | Issues |
|---|---|---|---|
| Weekly km (bar) | dashboard | km per Monday-week, `beginAtZero` | UTC week assignment |
| EF evolution (line + bars) | dashboard | EF (m/s per bpm) + counts per month | EF axis not zero-based (magnifies changes); n=1 months plotted without marking; series distinguished by colour only |
| EF trend (line) | compare | EF per month | same as above |

### 2.5 AI prompts (class E)

| Prompt | Content | Instructs the AI to |
|---|---|---|
| `/ai` | last 30 runs + whole-history totals + weekly km | judge overtraining, lactate threshold evolution, ideal week, HM/M prediction, injury risk |
| `/next-training` | same context + user parameters | give paces, HR zones, pacing, load, nutrition |
| `/rate-training` | one activity + laps with labels + context | judge the session, give a score out of 10 |
| `/compare/prompt` | period stats, EF, 150 bpm pace, app verdict | judge progress and explain its causes |

---

## 3. Issues

Severity levels:

- **P0**: breaks trust or security
- **P1**: major correctness problem
- **P2**: reliability or edge case
- **P3**: UX
- **P4**: visual polish

### P0

#### 0.1 Strava client secret committed to a public repo (VERIFIED)

- **Evidence:** `test-strava-connection.sh:22-23` hard-codes a fallback `CLIENT_ID` and `CLIENT_SECRET`. It has been there since the initial commit `97867b6`. The GitHub API returns HTTP 200 for `wodbyser/running_strava`, so the repo is public.
- **Not affected:** `.env` is git-ignored and was never committed (`git log -- .env` is empty). No token is logged anywhere.
- **Fix:** the owner rotates the secret; remove the fallback from the script. **Decision:** no history rewrite (Q9).
- **Validation:** `git grep` finds no secret; the script refuses to run without `.env`.

#### 0.2 Max pace is wrong (VERIFIED INCORRECT)

- **Evidence:** `FrontendController.kt:242` passes km/h into a function that expects m/s.
  - Activity 20314291036: `max_speed` = 4.68 m/s, displayed as **"0:59 /km"**.
  - Expected: 1000/4.68 = 213.7 s → **3:33 /km** (3:34 if rounded).
- **Fix:** `calculatePace(a.maxSpeed.toDouble())`.
- **Validation:** unit test 4.68 m/s → 3:33.

#### 0.3 Cadence per foot shown as spm/rpm (VERIFIED)

- **Evidence:**
  - DB values range from 52.3 to 86.7.
  - The UI shows "82 spm" on the coach and dashboard pages and "Cadence" on the detail page.
  - Prompts say "81.7 rpm" (`AiAnalysisService.kt:276, 387`).
  - The CSV cadence column has no unit.
- **Why it matters:** a real cadence of about 164 spm is shown as 82 spm, which reads like an alarming value. The AI may give wrong advice.
- **Fix:** multiply by 2 for run types, label "spm (beide voeten)" (Q3).
- **Validation:** test 82.1 → 164.

#### 0.4 PBs are not bests over the distance (VERIFIED INCORRECT)

- **Evidence** (`FrontendController.kt:854-884`, live `/pbs`):
  - "5 km" PB 19:23 is a **4897 m** race (1163 s moving time).
  - "10 km" PB 41:19 is a 9923 m run.
  - Selection is by pace, which favours shorter runs.
  - It uses moving time; a race should use elapsed time.
  - "Marathon" PB is a 42.2 km outing at 7:57/km (5u35m).
  - There is no 1 km PB at all.
  - `formatDuration` drops the seconds for durations over 1 h ("1u30m").
- **Fix:** compute the fastest segment over the exact distance from the stored distance/time streams (Q1). Cross-check against Strava best efforts where available.
- **Validation:** golden cases:
  - 21.9 km run: HM time interpolated at exactly 21097 m.
  - 10 km run containing a fast 5 km: that 5 km is found.
  - 4.9 km run: not a 5 km PB.

### P1

#### 1.1 "Interval" detection flags any fast segment (VERIFIED, probe + real data)

Probe results (synthetic laps):

| Case | App output |
|---|---|
| Progression 10×1 km, 6:00 → 4:30 | "4x 1000m intervals" |
| Easy run with one GPS-glitch lap at 3:10 | "1x 1000m @ 3:10" |
| Easy 5 km + 500 m finishing kick | interval session |
| 2 km WU + 5×1 km @4:30 + 4×100 m strides | "warming-up 7.00 km @ 4:51 \| 4x 100m" (the tempo vanishes into the "warming-up") |
| Hill repeats @5:10 up / jog down @6:30 | **the 2 km warm-up is classified as a REP** |
| Hill repeats @5:25 / 6:00 | nothing detected |
| 8×400 @3:50 + 400 float @4:45 + 2 km WU @5:45 | floats labelled "herstel": the exact scenario the code comment claims to handle fails |
| Pyramid 400-800-1200-800-400 | every rep becomes its own "set"; jogs become "setpauze" |
| Race, even pace | not an interval: correct, but it then counts as an "easy run" in EF |
| Time-based 6×3 min | correct |
| Missing-HR 6×800 | structure correct |

Real data, using activity names as weak labels:

| Name category | Flagged as interval | Not flagged |
|---|---|---|
| interval / pyramid | 48 | 0 |
| tempo / threshold / progression | 35 | 1 |
| easy / recovery / duurloop | **21** | 102 |

This is why the profile says "Intervaltrainer 2.2x/week".

- **Fix** (Q2 decision):
  - a stricter classifier: at least 2 similar reps, a glitch/outlier filter, and a separate tempo/steady-block category;
  - Strava `workout_type` as an extra signal;
  - races excluded from easy EF.
- **Validation:** golden-set tests for every case above, plus the name-label confusion table as a regression metric.

#### 1.2 Efficiency factor mixes populations (VERIFIED by code)

- **Evidence:** `PeriodComparisonService.kt:112-114, 120-122, 151-161`.
  - Mean speed is taken over all runs/laps, but mean HR only over those that have HR. 54 runs have no HR.
  - It is a ratio of unweighted means.
  - Races, tempo runs without laps and trail runs all land in "rustige lopen".
- **Fix:** only use items that have both HR and speed, weight by time, and exclude races and tempo runs from easy EF.
- **Validation:** a test with a mixed-HR set and a hand-computed EF.

#### 1.3 Verdict overstates small differences (VERIFIED)

- **Evidence:**
  - The threshold is ±3% with no minimum n and no measure of spread.
  - Live: interval EF "+4.3% verbeterd" while the rep mix differs (208 vs 144 reps).
  - Lap-average HR of short reps lags behind effort, so rep EF depends on rep length.
  - A single run per period still produces a verdict.
- **Fix:** minimum 5 easy runs and 3 interval sessions per period, otherwise "onvoldoende data" (Q8). Show the spread and word the verdict as an "indicatie".
- **Validation:** tests at n = 4 / 5 and at the threshold.

#### 1.4 150 bpm reference pace uses an invalid model (VERIFIED)

- **Evidence:** it assumes speed ∝ HR through the origin. Physiologically HR ≈ a + b·v. The interval figure is extrapolated from 171 to 150 bpm.
- **Fix:** remove it (Q7).

#### 1.5 Race predictions: optimistic and incoherent (VERIFIED)

- **Evidence:**
  - `predictFromAnchors` picks the **minimum** predicted time over all anchors. That biases predictions upward by noise.
  - Interval reps with rest in between are used as anchors. Riegel assumes continuous maximal efforts of roughly 3.5–230 min.
  - Live "Vormvoorspelling": 3 km at 3:43/km (from a 200 m rep, "verre extrapolatie") next to 5 km at 4:37/km. The table is internally inconsistent.
  - The 1 km prediction comes from a 400 m rep.
  - Output is shown to the second, with no range.
- **Fix** (Q6):
  - anchors are only continuous efforts (races, best efforts of 3 min or more, hard whole runs);
  - use the median instead of the minimum;
  - round the result and show it as a range;
  - show no prediction when there are too few anchors.
- **Validation:** paces increase monotonically with distance; reps are never an anchor; hand-computed Riegel values.

#### 1.6 Times in UTC (VERIFIED)

- **Evidence:**
  - Activity 20314291036 shows "24/09/2026 17:04"; the local start was 19:04 CEST.
  - Weekly and monthly grouping use the UTC date.
  - Filters use `ZoneId.systemDefault()`.
  - Today 0 of 520 runs have a different local vs UTC date, but a Monday 00:30 run would land in the previous week.
- **Fix:** use the activity's local time from its `timezone` field everywhere (Q4).
- **Validation:** near-midnight golden cases (Sunday 23:30 local, Monday 00:30 local).

#### 1.7 Unweighted dashboard / profile averages (VERIFIED)

- **Evidence:** `FrontendController.kt:64-66`, `CoachService.kt:197-199`.

  | Metric | App (unweighted) | Weighted |
  |---|---|---|
  | Pace | 5:49/km (mean of speeds) | **5:58/km** (total distance / total time) |
  | HR | 147.4 | 148.3 (time-weighted) |

- **Fix:** weighted averages (Q5, approved meaning change).

#### 1.8 AI prompts ask for undeterminable conclusions (VERIFIED)

- **Evidence:**
  - `/ai` asks about lactate threshold evolution, overtraining and injury risk. The app has no lactate, HRV, sleep or complaint data, and the prompt contains no instruction to say "niet bepaalbaar".
  - Heuristic output ("Intervallen: …") is presented as fact.
  - "Totaaloverzicht" covers all 2.4 years but follows "Laatste 30" without a label.
  - Weekly km omits zero weeks (2024-05-06 is followed directly by 2024-07-01).
- **Fix:** Phase 3.

### P2

| # | Issue | Evidence | Fix |
|---|---|---|---|
| 2.1 | NaN display | `average()` on an empty list when runs exist but none have HR or cadence (`FrontendController.kt:64-65`, `AiAnalysisService.kt:288`). 54 HR-less runs make this reachable. | null → "-" / "onvoldoende data" |
| 2.2 | Inconsistent pace rounding | truncation vs rounding: 5:59 vs 6:00 for the same lap | one shared rounded formatter in `core` |
| 2.3 | Raw floats and truncation in prompts | "154.0 bpm", "81.7 rpm", "306.3 W", "11.0 m"; "Tijd: 49 min" and "569 uur" truncated | uniform formatting |
| 2.4 | Resting-HR estimate meaningless | real data: p0.5 of in-run HR = 96, clamped to 85, labelled "geschat uit HR-data" | require user input; otherwise no Karvonen |
| 2.5 | Max HR presented as fact | p95 of run maxima (196, outlier 203); zone boundaries truncated and overlapping (117-137 / 137-156) | label "schatting"; half-open ranges |
| 2.6 | Zone texts debatable | "80-90% = 10 km–5 km tempo" (5 km pace is usually >90% HRmax), "vetverbranding", "Karvonen nauwkeuriger" | neutral wording |
| 2.7 | Profile labels arbitrary | "Elite" at ≥70 km/week, etc. | label as app classification; drop "Elite" |
| 2.8 | Threshold sensitivity | real data: interval sessions 170 / 159 / 149 at ratios 1.12 / 1.15 / 1.18, i.e. 21 sessions (13%) flip within ±20% of the margin; 5 "interval" sessions have ≤2 reps (e.g. "W2 T5 Duurloop") | covered by 1.1 |
| 2.9 | Sync gaps | `after = latest startDate` misses late uploads with an earlier start; edits on Strava are never re-fetched | overlap window |
| 2.10 | State changes over GET | `GET /sync`, `/fetch-all`, `/backfill-laps` | POST only |
| 2.11 | Plain DB password | `admin` in `application.yml` (local only) | env var |
| 2.12 | Inconsistent period boundaries | `filterActivities` uses an inclusive end, `PeriodComparisonService` an exclusive one | one convention |

---

## 4. Trust table

| Feature | Method | Class | Scientific basis | Tested? | Status |
|---|---|---|---|---|---|
| Distance, time, HR per activity | Strava as-is | A | – | checked against DB | VERIFIED |
| Activity time and date | UTC shown as local | A | – | live | INCORRECT |
| Cadence | per foot, labelled spm/rpm | A | – | live | INCORRECT |
| Max pace | km/h passed as m/s | B | – | live | INCORRECT |
| Pace per activity/lap | 1000/v, truncated | B | exact | code | VERIFIED WITH LIMITATIONS |
| Dashboard avg pace/HR | unweighted mean | B | – | DB comparison | INCORRECT |
| Weekly km | sum per UTC week | B | – | code | VERIFIED WITH LIMITATIONS |
| PBs | whole run ±5% | B/D | – | live | INCORRECT |
| %HRmax zones | textbook percentages | C | established | code | VERIFIED WITH LIMITATIONS |
| Karvonen zones | HRR formula | C | established | code | VERIFIED WITH LIMITATIONS |
| Max-HR estimate | p95 of run maxima | D | none | – | HEURISTIC BUT REASONABLE |
| Resting-HR estimate | p0.5 of in-run HR | D | none | real data | SCIENTIFICALLY QUESTIONABLE |
| Race predictions | Riegel 1.06, min over anchors incl. reps | C/D | Riegel 1981 | live | SCIENTIFICALLY QUESTIONABLE |
| LapClassifier | 2-means, ratio 1.15 | D | none | probe + real data | HEURISTIC / NEEDS VALIDATION |
| WorkoutStructure | 10% / 15% tolerances | D | none | 1 test + probe | HEURISTIC / NEEDS VALIDATION |
| Runner profile | fixed cut-offs | D | none | – | HEURISTIC / NEEDS VALIDATION |
| Efficiency factor | mean v / mean HR | D | EF known as a trend proxy | code | INCORRECT |
| 150 bpm reference pace | v ∝ HR | D | none | – | SCIENTIFICALLY QUESTIONABLE |
| Compare verdict | ±3%, no minimum n | D | none | live | SCIENTIFICALLY QUESTIONABLE |
| AI prompts (4) | text | E | – | live | INSUFFICIENT EVIDENCE |
| JSON/CSV export | raw fields | A | – | code | VERIFIED (CSV cadence has no unit) |

---

## 5. Decisions (answered 2026-09-25)

| # | Question | Decision |
|---|---|---|
| Q1 | PB definition | **Own best efforts from streams**: fastest segment over the exact distance, cross-checked against Strava best efforts |
| Q2 | Interval definition | **Stricter classifier + Strava `workout_type`**: ≥2 reps, glitch filter, tempo category, races excluded from easy EF |
| Q3 | Cadence | **×2, shown as spm (beide voeten)** in the UI, prompts and CSV |
| Q4 | Timezone | **Activity local time** everywhere |
| Q5 | Dashboard averages | **Weighted**: total distance / total time, time-weighted HR |
| Q6 | Race predictions | **Continuous efforts only**, median, rounded, as a range; nothing when there are too few anchors |
| Q7 | 150 bpm reference pace | **Remove** |
| Q8 | Verdict minimum n | **≥5 easy runs and ≥3 interval sessions** per period |
| Q9 | Secret in git history | **Rotate + remove from script**, no history rewrite |

---

## 6. UX/UI findings (separate from correctness)

1. **Navigation.** 8 flat items with no grouping and no mobile menu.
2. **Estimates look like measurements.** Max HR, zones, predictions, lap types and profile type have no visual marker.
3. **Confusing detail labels.** "Max snelheid" is shown as a pace, and "Cadence" and "Cadans" are mixed.
4. **Inconsistent duration formats.** "1u30m", "19m23s" and "1:30:06" all appear; durations over 1 h drop the seconds.
5. **Sync feedback.** Sync and fetch are long synchronous requests. The only feedback is a "Bezig met synchroniseren..." text and flash messages; there is no progress indicator.
6. **EF charts.**
   - The axis is not zero-based.
   - Months with n=1 are plotted without a marker.
   - The series are distinguished by colour only.
7. **Empty states.** There is no explicit state when a filter returns zero runs; stat cards show "-" or "NaN".
8. **Unlabelled CSV unit.** The CSV cadence column has no unit.
