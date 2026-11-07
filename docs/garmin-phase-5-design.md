# Garmin Phase 5 design: race prediction and readiness

Date: 2026-11-06. Status: DESIGN APPROVED by the owner 2026-11-06 (P5-0 to P5-13 answered in session, section 9; the agent
set the approval on his written instruction). BUILT 2026-11-06 (sixteenth session), PHASE APPROVED by the owner 2026-11-07:
build notes and results in sections 8 and 11. Algorithm version for everything below: `race-v1`.

Scope (roadmap Phase 5, confirmed by the owner in session 2026-11-06: "Hele lijst zoals roadmap"): backlog #49 race
performance prediction, #50 race readiness, #51 goal feasibility, #52 race pace recommendation, #53 pacing strategy,
#54 taper analysis, #55 race day readiness check, #56 performance confidence interval, #58 post-race analysis, #59 race
preparation retrospective. #57 (race simulation) stays Later. Nothing else.

Owner decisions taken in the same session (2026-11-06):

- **Goal input:** a form in the app (distance, date, optional target time), stored in a new table (P5-5).
- **What is a race:** the Strava marking `workout_type = 1` only. No automatic race detection in the analytics.
- **Candidate list:** the owner asked for a one-off list of runs that look like races but are not marked, so he can check
  them and mark them on Strava himself (section 1.3). Whether this list should also live on the page is P5-7.
- **Example goal:** a 5 km race on 2026-12-05, target time 19:00 (P5-0). Entered by the owner through the form after the
  build; not seeded.
- **Marked after the candidate list:** the owner marked three of the listed runs as races on Strava (two half marathons
  and one long trail race). All three are older than 30 days, so they reach the app only after the full re-read of P5-6.

Basis: `GARMIN_API.md` sections 4.3 and 8, `GARMIN_ROADMAP.md` Phase 5, `docs/garmin-audit-remediation.md` (Phase A
Q6: race predictions), `docs/garmin-phase-2-design.md` (recovery norms), `docs/garmin-phase-3-design.md` (critical
speed #22, session types, best efforts), `docs/garmin-phase-4-design.md` (TRIMP load, session readiness #81),
`docs/ui-guidelines.md` (binding for every page).

Fact labels: VERIFIED (code, live data, or Python source), DOCUMENTED, UNVERIFIED, DECISION (owner), PROPOSAL (agent
choice, owner may change). Questions for the owner: section 9 (P5-0 to P5-13).

## 0. Reading guide: what this phase can and cannot do

Phase 5 answers four questions: "what time can I run now", "is my goal realistic", "how do I pace it" and "what
happened at past races". Three limits shape the design:

1. **Few races.** 9 marked races in the data (section 1.2), of which 7 have Garmin recovery data before them. Every
   analysis over races (#54, #58, #59, the accuracy behind #56) works on a handful of points. The page lists them one by
   one and draws conclusions only above a minimum (2.6). `Onvoldoende data` is the correct answer most of the time.
2. **A prediction is not a promise.** Riegel and critical speed assume maximal efforts and equal training for every
   distance. Fastest segments inside training runs are rarely maximal, so predictions lean slow; for the marathon
   Riegel leans fast (the owner never ran one). The page always shows a range (#56), the anchors behind it, and how far
   off the method was on the owner's own past races.
3. **No outcome label for "ready".** As in Phase 4, readiness (#50, #55) lists components that argue for or against,
   with no number (spec rule 6). It never says "klaar", "veilig" or "je loopt X".

## 1. Inputs and data census

### 1.1 Inputs (all already stored, except the goal)

| Source | What | Used for | Status |
|---|---|---|---|
| Strava `activities` | `workout_type` (1 = race), distance, moving/elapsed time, average and max HR, elevation, name | race list, anchors, post-race | VERIFIED |
| Strava `activity_streams` | `time`, `distance`, `heartrate`, `grade_smooth` | best efforts, splits, pacing history, critical speed | VERIFIED |
| Phase A code | `RacePredictor` (Riegel, median, 0.4 to 2.5 x ratio, 3.5 to 230 min, rounding), `BestEffortFinder`, `BestEffortService` | #49 | VERIFIED, Phase A APPROVED |
| Phase 3 code | `CriticalSpeed` (#22), `SessionClassifier`, steady-run EF, `GradeAdjusted`, `PerformanceService` cache | #49 second method, #50 fitness | VERIFIED, Phase 3 APPROVED |
| Phase 4 code | TRIMP per activity, daily load, `HardSessions`, `SessionReadiness` (#81), fatigue signals (#11) | #50, #54, #55, #59 | VERIFIED, Phase 4 APPROVED |
| Phase 2 code | `RecoveryAnalyzer`, own-norm bands, sleep target | #50, #55, #58 | VERIFIED, Phase 2 APPROVED |
| Garmin `garmin_hr_zones` | `RUNNING` row: max HR, resting HR, lactate threshold HR | HR reference for #58 (threshold HR); not a race filter (P5-3) | VERIFIED present; derivation UNVERIFIED |
| **New:** goal | distance, date, optional target time, optional name | #51, #52, #53, #55 | to build (P5-5) |

Not available: course profiles of future races, weather, official chip times (only when the owner typed them in the
activity name; never parsed, P5-12), Garmin race predictor (not in the client, UNVERIFIED), Garmin Training Readiness
(spec 2.4).

### 1.2 Data census (2026-11-06, read-only counts on the local database copy, data up to 2026-10-31, nothing committed)

The local database was started for these counts and stopped again. The live app now runs on the Windows host; its
database may hold newer activities. The build re-checks the counts there through the app itself (section 8).

| Item | Result |
|---|---|
| Runs | 535, 2024-05-01 to 2026-10-31 |
| Marked races (`workout_type = 1`) | 9: three of about 5 km (4.82 to 4.90 km measured), four of about 9.5 to 10 km (8.56 to 9.92 km), three half marathons (21.05 to 21.28 km). Dates 2024-12-01 to 2026-10-11 |
| Races with a stream | 9 of 9 |
| Races with Garmin HRV on the 28 days before | 7 of 9 (the two races of December 2024 fall before or at the start of the Garmin data) |
| Races with internal load (TRIMP) on the 28 days before | 9 of 9 (load starts 2024-10-12) |
| Garmin `RUNNING` zone row | max HR 197, resting HR 52, lactate threshold HR 184 (the Phase 4 census said 196; the row was updated by a sync since) |
| Garmin `eventType = race` | 1 activity, and it is already marked on Strava. Not useful as a source |
| A marked race at a low average HR | 1: a 9.8 km race of December 2025 at an average HR of 154 (78 % of max). The owner keeps it as a race and an anchor (P5-3: his HR is sometimes wrong) |
| Measured distance of 5 km races | 4.82 and 4.90 km (GPS or course). Predictions at "5 km" and the post-race check at the measured distance differ for this reason (P5-12) |

Consequences:

- **Accuracy check (#56) on own races:** at most 9 points, fewer once the race itself and races with too few anchors
  before them are left out. The rule in 3.2 needs at least 5.
- **#54 taper and #59 retrospective:** 9 races at most, split in two groups for #59. Both will mostly list, not
  conclude.
- **Marathon:** no marathon in the data. Anchors within 0.4 to 2.5 x of 42.195 km are half marathons and long hard runs
  only; the prediction carries a fixed warning (3.1).

### 1.3 Runs that look like a race but are not marked (owner request, one-off list)

Search rule used (counts only, read-only): runs without `workout_type = 1` and with any of: a race word or an official
time in the name, an average HR of at least 170 bpm for at least 12 minutes, a pace under 4:45 per km for at least 15
minutes, or Garmin event type `race`. 392 broad matches were narrowed by hand to the list below. The list itself (names,
dates) is given to the owner in the session and is **not** committed here (identifiers rule). Summary:

| Group | Count | Note |
|---|---|---|
| Very likely races (official time or race name in the title) | 3 | two half marathons (one without HR, 2024-10) and one long trail event |
| Possible (event-like name, or a long trail) | 2 | owner to judge |
| Hard training efforts, not races (time trial, tempo, "hoog aeroob") | 4 | not races; they already count as anchors when they meet the hard-run rule |

**Important for the owner:** the daily Strava sync re-reads only the last 30 days (`SyncStravaDataImpl.RECHECK_DAYS`).
A race marking added now to an activity older than 30 days does **not** reach the app by itself. P5-6 proposes a
button that re-reads all activity summaries once (list calls only, about 6 Strava calls, no detail or stream calls
unless the recording itself changed).

## 2. Common rules

### 2.1 Days and time

Activity-local calendar dates (Phase A Q4), Europe/Brussels for "today". A goal date is a calendar date. "Days to the
race" counts calendar days; the race day is day 0. Today's activities are not yet complete and never part of a
baseline (Phase 4 rule).

### 2.2 What counts as a race

- A **race** is a run with Strava `workout_type = 1` (DECISION 2026-11-06). Nothing else is called a race in any
  analysis. Trail races follow the same rule.
- **Every marked road race counts** as an anchor and in the accuracy check, whatever its heart rate (DECISION P5-3,
  2026-11-06: the owner's HR is sometimes wrong, and a marked race is a race). There is no HR filter on races.
- **Trail races** (elevation above 15 m/km, `TerrainClass`) are never prediction anchors and never in the accuracy
  check: road predictions do not apply to them. They are listed in #58 with "trail, geen vergelijking met een
  wegvoorspelling".
- A **goal** is matched to a race when a race exists on the goal date with a measured distance within 10 % of the goal
  distance. The matched race is then the "actual" of #58 for that goal.

### 2.3 Anchors (Phase A, unchanged, plus two filters)

Anchors are the continuous efforts Phase A allows (audit Q6, `CoachService.anchors`):

1. marked road races (2.2), whole activity, moving time;
2. per standard distance (1 km, 5 km, 10 km, half marathon, marathon) the fastest segment inside a run
   (`BestEffortFinder`), at least 3.5 minutes, trainers excluded;
3. whole runs with average HR at least 85 % of max that the session classifier calls steady (not interval, not tempo).

Interval reps are never anchors. **New filters:** the trail filter of 2.2 (PROPOSAL), and a default window of
**180 days** before the evaluated day (DECISION P5-2; Phase A uses 12 months on the coach page). The window is
adjustable on the page.

### 2.4 Walk-forward rule (spec rule 10)

Every number about a past race (#56 accuracy, #58 predicted time, #54 and #59 "better or worse than predicted") is
computed from anchors dated **strictly before** the race day. The race itself and later activities never enter its
own prediction. A test adds a later activity and checks no earlier prediction changes.

### 2.5 Labels and wording

- `[gemeten]` race times, distances, HR; `[berekend]` splits, sums, differences; `[app-schatting]` predictions, ranges,
  paces, readiness; `[onbeschikbaar]` with the reason.
- Safety line on every page with recovery or readiness (Phase 2, unchanged): "Geen medische beoordeling. Pijn of
  klachten wegen zwaarder dan elke score. Normale waarden zijn geen bewijs dat een blessure of ziekte hersteld is."
- Prediction note on every prediction: "Schatting uit je eerdere inspanningen, geen belofte. Weer, parcours en de dag
  zelf maken het verschil."
- Words never used: "veilig", "klaar", "je loopt X" (the page says "je huidige voorspelling"), "optimaal", "beste
  taper", "haalbaar" as a verdict without the range. #51 uses the plain bands in 3.4.
- Every relationship (#54, #59) carries "Verband, geen oorzaak".

### 2.6 Minimum data (rule 4, abstain with counts)

| Item | Minimum |
|---|---|
| #49 prediction per distance | 2 anchors within 0.4 to 2.5 x of the distance (Phase A) |
| #49 critical speed method | the Phase 3 fit passes (3 anchors, ratio 2, R2 0.98, range 5 %), and the target time within 3 to 30 min |
| #56 range from own race errors | 5 marked road races with a walk-forward prediction; below that the range is the anchor spread only |
| #51 feasibility | a prediction for the goal distance; the improvement comparison needs 26 weeks of weekly predictions |
| #50 readiness | at least 2 components with data (Phase 4 #81 rule) |
| #53 own pacing history | 3 marked road races with a stream |
| #54 taper | list from 1 race; summary from 6 races with load data and a walk-forward prediction |
| #55 race week check | a goal with a date 0 to 7 days ahead; components as Phase 2 / Phase 4 |
| #58 post-race | 1 race; "voorspeld" needs the #49 minimum before the race |
| #59 retrospective | list from 1 race; group comparison from 4 races per group |

All values are PROPOSALS and config values (`garmin.race.*`), labelled UNVERIFIED (no external validation).

## 3. Metrics

### 3.1 #49 Race performance prediction

- **Main method (P5-1): Riegel median, Phase A unchanged.** For target distance `D`: every anchor `(d, t)` with
  `D / d` in 0.4 to 2.5 gives `t x (D / d)^1.06`; the prediction is the median, rounded (5 s under 20 min, 15 s under
  1 h, whole minutes above). At least 2 anchors. Hand check: anchors 5 km in 20:00 and 10 km in 42:00 give for 10 km
  `1200 x 2^1.06 = 2501.9 s` and `2520 s`, median `2510.9 s`, rounded to 41:45 (15 s step: 2505 s).
- **Second method, 5 km and shorter targets only:** critical speed of Phase 3 (#22): `t = (D - D') / CS`, only when the
  Phase 3 fit passes and `t` lies in 3 to 30 minutes. Shown beside the Riegel number as "tweede methode", never
  averaged with it (spec: never average incompatible estimates).
- **Distances:** 5 km, 10 km, half marathon, marathon, plus the goal distance when it is not one of them.
- **Marathon warning (fixed):** "Je liep nog geen marathon. Riegel is voor de marathon vaak te optimistisch, zeker
  zonder lange duurlopen van 30 km of meer." The prediction is still shown when the anchor minimum is met.
- **Evolution:** the same prediction computed every Monday for the last 52 weeks (walk-forward by construction), drawn
  as a line per distance with the owner's actual races as dots. This is the main chart of the page.

### 3.2 #56 Performance confidence interval

Two parts, shown together, never merged into one unexplained band:

1. **Anchor spread** (always): the lowest and highest single-anchor prediction (Phase A `low`, `high`).
2. **Own accuracy** (when at least 5 marked road races have a walk-forward prediction, 2.4): the error of each past
   prediction, `(actual - predicted) / predicted`, at the race's measured distance. The page shows the median error
   (bias) and the smallest and largest error. The range shown is `prediction x (1 + smallest error)` to
   `prediction x (1 + largest error)`. With 5 to 9 races the min and max are used (no percentiles on so few points).
   PROPOSAL, P5-4.
3. Below 5 races: "Nog te weinig wedstrijden om te meten hoe goed deze voorspelling voor jou klopt (n = x, minstens 5
   nodig). De bandbreedte is alleen de spreiding van je ankers."

Validation baseline (rule 10): the same races predicted by "your last marked race, converted with Riegel" (one
anchor). The page reports both mean absolute errors. The main method is not hidden when it loses (it is the Phase A
approved method); the page says which was closer.

### 3.3 #52 Race pace recommendation

- For each prediction: the pace range from the range of 3.2 (`range / distance`), and the middle pace.
- With a goal: goal pace next to it, and the difference in seconds per km.
- Wording: "Tempo dat bij je huidige voorspelling past: 3:52 tot 4:01 per km". Never "loop dit tempo".

### 3.4 #51 Goal feasibility

Needs a goal with a target time (P5-5). Three parts:

1. **Goal against the prediction range** (3.2): band `binnen je bandbreedte`, `sneller dan je bandbreedte` (with the
   gap in % and in s/km), `trager dan je voorspelling`. Colours mean distance from the prediction, not good or bad.
2. **Needed improvement against own history:** needed change = goal / today's prediction - 1, per week until the race.
   Compared with the owner's own weekly prediction series (3.1, same distance or the nearest with data): the share of
   past windows of the same length in which the prediction improved at least that much. Example sentence: "Je
   voorspelling moet nog 2,1 % sneller worden in 5 weken. In je geschiedenis gebeurde zo'n verbetering in 3 van de 40
   periodes van 5 weken." Needs 26 weeks of the weekly series. PROPOSAL.
3. **Specificity hint** (from #50): whether recent sessions contained running at or faster than goal pace (minutes
   within 3 % of goal pace or faster in the last 6 weeks, from streams, warming-up excluded by the 3.5 min rule of
   segments). Shown as a number, not a verdict.

No "haalbaar / niet haalbaar" verdict. The hero states the band and the history sentence.

### 3.5 #53 Pacing strategy

- **Default plan: even pace** at the chosen target (goal time if set, else the middle prediction), as a split table per
  km (per 5 km for half and marathon), with cumulative times.
- **Variant: rustige start**: first 1 km (5 km race) or first 3 km (longer) 2 % slower, the rest evenly faster so the
  total time is the same. PROPOSAL; the literature supports even or slightly negative pacing for distance races
  (cited in "Meer uitleg"), the 2 % is an app choice.
- **Own pacing history** (needs 3 marked road races with a stream): for each past race the first-half and second-half
  pace from the stream, and the share of races with a slower second half. Example: "In 5 van je 7 wedstrijden liep je
  de tweede helft trager (mediaan 2,3 %)."
- No course profile (not available); the page says "parcours en wind zitten niet in dit plan".

### 3.6 #50 Race readiness (fitness and recovery kept separate)

Shown for the goal race (or, without a goal, as "algemene wedstrijdvorm" with parts 1 to 3 only). Same shape as Phase 4
#81: each component `pleit ervoor` / `pleit ertegen` / `onbekend`, a level from the count of counter-signals
(`Geen tegensignalen`, `Eén tegensignaal`, `Meerdere tegensignalen`, `Onvoldoende data`), no number.

| Group | Component | For | Against |
|---|---|---|---|
| Fitheid | Prediction trend: today's prediction against 8 weeks ago | faster or equal (within 1 %) | slower by more than 1 % |
| Fitheid | Phase 3 condition trend (#21) | 2 or more indicators better, none worse | 2 or more worse |
| Specifiek | Running at goal pace in the last 6 weeks (3.4 part 3) | at least 20 min in total | under 20 min |
| Specifiek | Longest run of the last 6 weeks, half marathon and longer only | at least 75 % of the race distance | below |
| Recente prestatie | Most recent marked race or anchor within 0.4 to 2.5 x | within the last 60 days | older or none |
| Herstel (short term) | Phase 4 #81 type "Wedstrijdtempo" level for today | no counter-signals | one or more |

The fitness rows and the recovery row are shown in separate blocks with their own headings ("Fitheid op lange termijn"
and "Herstel op korte termijn"), as the backlog asks. All thresholds are PROPOSALS (P5-9).

### 3.7 #55 Race day readiness check

Shown only from 7 days before a goal race to the race day itself; otherwise the section says "Verschijnt in de week
voor je wedstrijd (nog x dagen)". Components, each against the owner's own norm (Phase 2 bands):

- HRV, resting HR, sleep duration (also against the 7:30 target), Body Battery at waking, of today and the days since
  the check opened, as small bars;
- load of the last 7 days against the 28-day average (taper state, plain percentage, no zone);
- Phase 4 fatigue signals active today;
- days since the last hard session.

Same retrospective check is computed for every past race (the morning of the race), so #58 and #59 can show "hoe
stond je herstel die ochtend". No number, no "klaar".

### 3.8 #54 Taper analysis

For every race with load data:

- load (TRIMP) of the 7 days and of the 14 days before the race, as % of the average week of the 28 days before those
  14 days;
- the result: actual time against the walk-forward prediction (2.4), in %.

List always (one row per race, run name, Dutch date). Summary (rank correlation of taper % with result, permutation
p-value with a fixed seed, the Phase 4 `LoadStats`) only from 6 races with both values; otherwise "Te weinig
wedstrijden om een verband te zoeken (n = x, minstens 6)". No "best taper". Note: "Verband, geen oorzaak; je taperde
misschien meer voor belangrijke wedstrijden".

### 3.9 #58 Post-race analysis

For each race (newest first; a detail view per race):

- predicted (walk-forward, at the measured distance) and actual time, difference in % and s/km; for trail races the
  reason why there is no comparison;
- **possible explanations, listed as factors, not causes:** pacing (first against second half, 3.5), HR (average race
  HR against the median of the owner's earlier marked races of similar length, and against the Garmin lactate
  threshold HR), elevation per km against the anchors, measured distance against the nominal distance, recovery that
  morning (3.7 retrospective), taper (3.8), warm-up on the same day (a run of under 3 km in the 2 hours before);
- the chart: predicted against actual for all races (dot per race, line of equality).

### 3.10 #59 Race preparation retrospective

For each marked road race, the 8 weeks before it: average weekly km, average weekly load, number of long runs, number
of hard sessions, longest run, taper % (3.8). Shown as small multiples (one mini chart per measure, one bar per race,
coloured by "sneller dan voorspeld" / "trager dan voorspeld"). Group comparison (medians of the faster group against
the slower group) only when both groups have at least 4 races; otherwise the table is folded and the sentence explains
the minimum.

## 4. Pages (follow `docs/ui-guidelines.md` from the first version)

Proposal (P5-8): one new nav entry **"Wedstrijd"** with two pages and a JSON endpoint.

### 4.1 `/race` "Wedstrijd" (looking forward)

1. **Hero:** the next goal race (name, distance, Dutch date, days to go), today's prediction for that distance with its
   range, the goal against it (#51 band) in one or two sentences. Without a goal: the four predictions and a button to
   add a goal.
2. **Doelen** (form): distance (5 km, 10 km, half, marathon, other in km), date, optional target time and name; list of
   goals with delete. POST only.
3. **Voorspelde tijden** (#49, #56): tiles per distance (time, range, pace range); chart of the weekly prediction over
   52 weeks with race dots; anchors folded ("Waarop is dit gebaseerd?"); accuracy on own races.
4. **Tempo en schema** (#52, #53): pace range, even plan and "rustige start" variant as split tables, own pacing
   history.
5. **Wedstrijdvorm** (#50): fitness block and recovery block.
6. **Wedstrijdweek** (#55): only in the 7 days before.
7. **Mogelijk niet gemarkeerde wedstrijden** (if P5-7 is accepted): folded list with links to Strava, and the
   "Markeringen opnieuw ophalen" button (P5-6).
8. **Begrippen** (`RaceGlossary`: Riegel, kritische snelheid, anker, bandbreedte, taper, negatieve split).

### 4.2 `/race/history` "Terugblik op wedstrijden" (looking back)

1. Conclusion: number of races, how often the prediction was close (within the median error), the clearest pattern if
   any passes its minimum, else "nog geen duidelijke patronen".
2. **Voorspeld tegen gelopen** (#58) chart and list; a detail block per race (`/race/history?race={id}` or an anchor).
3. **Taper** (#54).
4. **Voorbereiding** (#59).
5. Begrippen.

### 4.3 Common UI rules

- Every window adjustable with the default shown (`RaceWindowSettings` via `fragments :: windowForm`): anchor window
  180 days, prediction chart 52 weeks, trend comparison 8 weeks, specificity and longest-run window 6 weeks, race-week
  check 7 days, taper 7 and 14 days, retrospective 8 weeks. Minimum counts scale with the window (70 % rule).
- Dutch dates via `PerfDates`, run names next to dates, links to `/activity/{id}`, gaps broken in lines.
- The coach page "Wedstrijdvoorspelling" and "Vormvoorspelling" tables (P5-8): replaced by one link card to `/race`, so
  the app shows one prediction, not two that can disagree.
- Checked in headless Chrome on real data on a second instance (port 8091) before reporting.

## 5. Code layout (after design approval)

| Module | Item |
|---|---|
| core `com.running.race` (new, pure, no Spring) | `RaceAlgorithm.VERSION = "race-v1"`, `Races` (race list, trail rule, goal matching), `Anchors` (Phase A anchors + filters, window), `Prediction` (#49, reuses `RacePredictor` and `CriticalSpeed`, weekly series), `Accuracy` (#56 walk-forward), `PacePlan` (#52, #53), `Pacing` (half splits from a stream), `Feasibility` (#51), `RaceReadiness` (#50), `RaceWeek` (#55), `Taper` (#54), `PostRace` (#58), `Retrospective` (#59) |
| core reuse, no result change | `RacePredictor`, `BestEffortFinder`, `CriticalSpeed`, `SessionClassifier`, `TerrainClass`, Phase 4 `Trimp`, `DailyLoad`, `HardSessions`, `SessionReadiness`, `LoadStats`, Phase 2 `RecoveryAnalyzer` |
| core SPI | `RaceGoalRepository` (list, save, delete) |
| runner `race/db` | `RaceGoalEntity`, table `race_goal` (id, distance_m, race_date, target_seconds nullable, name nullable, created_at). Additive, `ddl-auto: update` (P5-5) |
| runner `analysis/RaceService` | loads activities, streams and Garmin rows, reuses the Phase 3 and Phase 4 caches and warm-up, calls core |
| runner `analysis/RaceText`, `RaceGlossary`, `RaceWindowSettings` | view models and Dutch text |
| runner `frontend/RaceController` | `GET /race`, `GET /race/history`, `GET /race/data` (JSON, read-only); `POST /race/goal`, `POST /race/goal/{id}/delete`; `NoStateChangingGetTest` extended |
| Strava (P5-6, only if accepted) | `SyncStravaData` gets a "full recheck" variant (same code, `after = null`), `POST /strava/recheck-all`; existing sync behaviour unchanged |
| templates | `race.html`, `race-history.html`, nav entry "Wedstrijd", coach page link card |
| config | `GarminProperties.race` (`garmin.race.*`): minimum counts, variant percentage, specificity thresholds. Defaults = this document |

Computed on demand. If the first `/race` render after the warm-up takes more than 5 s on the real data, the build stops
and the owner decides (same rule as Phases 3 and 4). Logging: counts and the algorithm version only; no times, HR,
recovery values, names or goals.

## 6. AI prompt

No change by default (P5-13). The goal and predictions are not Garmin health data, but #50 and #55 combine them with
Garmin recovery values; any prompt block would need the Phase 2 `herstel=true` rule.

## 7. Tests (offline, hand-computed, synthetic data only)

| Area | Cases |
|---|---|
| Prediction (#49) | Riegel hand values (3.1 example 41:45); median of 3 and 4 anchors; no prediction from 1 anchor; anchor at ratio 0.39 and 2.51 excluded; **paces increase monotonically with distance** (5 km < 10 km < half < marathon pace) for several anchor sets; interval reps never anchors; a marked road race with a low average HR is still an anchor (P5-3); trail race excluded; 180-day window boundary (day 180 in, 181 out); marathon warning present; critical speed method only within 3 to 30 min and only when the fit passes |
| Walk-forward (2.4, #56) | a race's own time never enters its prediction; adding a later activity changes no earlier prediction; 4 races give the anchor spread only, 5 give the own-error range; min/max error range by hand; baseline "last race" error by hand |
| Pace (#52, #53) | pace range from the time range; even split table sums to the target; "rustige start" keeps the total time and is 2 % slower at the start; half-split pacing from a synthetic stream |
| Feasibility (#51) | three bands at their boundaries; needed % per week; history share counted by hand on a synthetic weekly series; abstain under 26 weeks; no verdict word in the text |
| Readiness (#50, #55) | each component alone flips its stance; fitness and recovery in separate blocks; fewer than 2 components gives `Onvoldoende data`; race week check visible on day 7 and 0, hidden on day 8 and after the race; no "klaar"/"veilig" in any text |
| Taper (#54), retrospective (#59) | taper % by hand; 5 races list only, 6 summarise; groups of 3 abstain, 4 compare; fixed-seed permutation reproducible |
| Post-race (#58) | factor list for a synthetic race (positive split, warm-up run, short measured distance); trail reason |
| Goals | save, list, delete via POST; GET never changes state (`NoStateChangingGetTest`); goal matched to a race on the date within 10 % distance, not at 11 % |
| Service, controller, page | `RaceService` with fakes; pages render with no races, no goal, no Garmin data; JSON has no key, token or coordinates; log capture shows no values |
| Strava recheck (if P5-6) | full recheck lists all pages and updates a changed `workout_type` of an old activity with no detail call; the normal sync still uses the 30-day window (existing tests unchanged) |

Existing tests: none expected to change. `CoachService` race prediction tests stay as they are; if the coach page
tables are replaced (P5-8) the service code may stay for the AI prompt, or its tests move with it; old and new values
go here and in the progress log.

## 8. Exit criteria (filled in at the end of the build, 2026-11-06)

| Criterion | Status |
|---|---|
| Design doc approved | DONE (owner, 2026-11-06) |
| Each metric has normal, abstain and boundary tests, including the roadmap's three (monotonic paces, hand Riegel values, no prediction with too few anchors) | DONE: `core/src/test/.../race/RaceTest.kt` and `HistoryTest.kt` (hand Riegel 41:45, monotonic paces over three anchor sets, one anchor gives no prediction, ratio 0.39/0.4/2.5/2.51, window day 180 in and 181 out, P5-3, trail, bands at their boundaries, taper by hand, groups of 3 and 4, fixed seed). Runner: `RaceServiceTest`, `RaceControllerTest` |
| Walk-forward accuracy report on the owner's races (#56) with the naive baseline | DONE on real data (local copy, data to 2026-10-31): 9 races, 9 with a prediction, median error -6.7 % (minus = faster than predicted), range -13.6 % to +1.5 %. Mean absolute error 4.8 % for the main method against 6.6 % for "last race converted with Riegel" (6 races compared); the main method was closer. Shown on `/race` |
| Sample counts and "verband, geen oorzaak" on every relationship | DONE: taper, retrospective, post-race factors and the group comparison carry the sentence and the counts |
| No health data, goal values, identifiers or coordinates in logs | DONE: log test (`RaceServiceTest`) checks counts and version only; JSON test checks no key, token or coordinates |
| Existing tests pass unchanged (or changes listed) | DONE: 528 tests pass (core 382, runner 146), 74 new. No existing test changed. `SyncStravaData` got a second method (`executeFull`), two new tests added to `SyncStravaDataImplTest`, the existing ones untouched. `CoachService` race prediction tests unchanged (the service code stays, only the page tables are replaced by a link card) |
| Census re-checked on the live data through the app | PARTLY: re-checked on the local copy through the app (535 runs, 9 marked races, 9 with streams, 7 with Garmin recovery on the morning, as in section 1.2). The live database on the Windows host was not reachable from here; the three new markings are not in the copy (P5-6 re-read needed) |
| Pages checked in a browser on real data | DONE: both pages in headless Chrome on port 8091 with the local copy (with a 5 km goal on 2026-12-05 in 19:00 and a second goal 3 days ahead to see the race week; both goals deleted again), at 1280 px, 1320 to 1700 px (menu) and 420 px |
| Owner approval of the phase | DONE: APPROVED by the owner 2026-11-07 (seventeenth session; the agent set the Approvals cell on his written instruction) |

## 9. Questions for the owner: ALL ANSWERED 2026-11-06 in session

Answers: P5-0 5 km on 2026-12-05, target 19:00; P5-1 Riegel plus critical speed; P5-2 180 days; **P5-3 changed: every
marked race counts, no HR filter** (his HR is sometimes wrong; the December 2025 race at 154 bpm counts); P5-4 to P5-13
all defaults accepted (P5-6 button yes, P5-7 list on the page folded yes, P5-8 coach tables replaced by a link card,
P5-13 no AI prompt change). Design approved by the owner 2026-11-06.


| # | Question | Default if not answered |
|---|---|---|
| P5-0 | Date and target time of the 5 km race in December (used as the example and the first goal) | none; the owner enters it in the form after the build |
| P5-1 | Prediction method | Riegel median of Phase A as the main number; critical speed (Phase 3) beside it for 5 km, never averaged |
| P5-2 | Anchor window | 180 days (adjustable); the coach page uses 12 months today |
| P5-3 | Races not run all-out (average HR under 85 % of max, one in the data) | ANSWERED: every marked race counts, no HR filter |
| P5-4 | Range (#56) | anchor spread always; plus the min-max error on own races from 5 marked road races |
| P5-5 | Goal storage | new table `race_goal` (additive, no change to existing tables); several goals, the nearest future one is the focus; target time optional |
| P5-6 | Old race markings reach the app only via a full re-read | button "Markeringen opnieuw ophalen" that re-reads all Strava activity summaries once (about 6 calls); daily sync unchanged |
| P5-7 | Keep the "mogelijk niet gemarkeerde wedstrijden" list on the page | yes, folded, with the search rule of 1.3, read-only |
| P5-8 | Pages `/race` and `/race/history`, nav "Wedstrijd"; coach page prediction tables replaced by a link card | accept |
| P5-9 | Readiness components and thresholds (3.6), race week check from 7 days before (3.7), no number | accept |
| P5-10 | Pacing: even plan plus a 2 % "rustige start" variant, own half-split history, no course profile | accept |
| P5-11 | Minimums: own-error range 5 races, taper summary 6, retrospective groups 4 per group | accept |
| P5-12 | Measured (GPS) distance for anchors and for post-race comparison; official times typed in names are not parsed | accept |
| P5-13 | AI prompt | no change |

## 10. Not in Phase 5

Race simulation (#57, Later); weather or course profiles; parsing official chip times from names; automatic race
detection in the analytics (the candidate list only helps the owner mark races on Strava); injury module (#60 to #68);
Garmin race predictor or VO2max (UNVERIFIED / UNAVAILABLE); any new Garmin endpoint call; any external AI call; any
schema change other than `race_goal`.

## 11. Build notes (2026-11-06): what was decided while building, results, open points

Everything in section 5 was built as designed. Where the build had to choose, it is listed here so the owner can overrule it.

Build decisions:

1. **Candidates list (P5-7).** The rule is: unmarked run of at least 3 km (no treadmill), strong = race word or a time in the name; weak = average
   HR of at least 170 bpm for 12 minutes or a pace under 4:45 per km for 15 minutes (not for interval sessions). Garmin event type is not used
   (one activity in the census, already marked). At most 15 strong and 10 weak are shown, folded, read only. On the local copy: 17.
2. **Anchors and trail.** Only marked trail races are left out, with all their anchors (design 2.2, 2.3). Hilly runs that are not marked as a race
   remain anchors, as in Phase A. Example on real data: a hilly run gives a "snelste marathon" of 5:13:23 in the anchor table; it falls outside
   Riegel's 230 minute limit, so it never enters a prediction, but it is visible. Open point 2 below.
3. **Range (#56).** Literal as designed: `prediction x (1 + smallest error)` to `prediction x (1 + largest error)`, from 5 races. Consequence on
   real data: the 5 km range is 17:40 to 20:45 around a Riegel median of 20:30, because one race in December 2024 was predicted 13.6 % too slow.
4. **Readiness without a target time.** The "doeltempo" and "langste run" rows need a goal with a target time (the longest run also needs a
   half marathon or longer). Without them the block has the prediction trend, the condition trend and the recent race or anchor. The condition
   trend gets stance "neutraal" with "geen duidelijke verandering" when no indicator is better or worse (stable or uncertain). A fourth stance
   "neutraal" was added to the Phase 5 block; it counts as data, not as a counter-signal.
5. **Specificity (#51 part 3).** Stretches at goal pace or faster (3 % tolerance, speed over a 30 s window) count only when they last 3.5 minutes
   or more. Strides and short reps do not count.
6. **Pacing history (#53).** Halves from the stream (elapsed time, same distances); trail races are not part of it.
7. **Plan (#53).** Only for the next goal; without a goal the page says so. Splits per km up to 10 km, per 5 km above. The table shows the
   time at the end of each part and the pace; "rustige start" is 2 % slower over the first 1 km (5 km) or 3 km (longer), the rest evenly faster.
8. **Race week (#55).** Uses the Phase 2 recovery report of each day from the start of the window to today, the Phase 4 fatigue signals and days
   since the last hard session, and the load of the last 7 days as a percentage of the average week of the last 28 days. Visible 0 to 7 days
   before the goal date (adjustable, 3 to 14).
9. **Taper, retrospective.** As designed. The taper summary is a rank correlation with a permutation p-value (fixed seed) from 6 races. On
   real data: rho 0.62, p 0.08 with 9 races, which the page says is not a clear relationship. Group comparison needs 4 races per group (not
   reached: 8 faster against 1 slower).
10. **Strava re-read (P5-6).** `POST /race/strava-recheck` (button on `/race`, section "Mogelijk niet gemarkeerde wedstrijden"). Core:
    `SyncStravaData.executeFull()` runs the normal sync code with no date filter. Not pressed during verification (no Strava call was made).
11. **Warm-up.** `RaceService` fills its per-activity caches (best efforts, race halves) in the background after start-up (`garmin.race.warm-up`).
    Timing on the local copy: first `/race` request during the start-up warm-up 3.7 s, later 2.6 s; `/race/history` 2.0 s. The 5 s rule holds.
12. **Data used.** `LoadService.snapshot()` was added (sessions, days, Garmin series and the overview with the readiness) so Phase 5 reuses the
    Phase 4 numbers without a second implementation. No change to any Phase 4 result.
13. **Menu.** Adding "Wedstrijd" made the 13 item menu overflow on screens of 1080 to about 1500 px. Changes in `layout.html`: the burger menu
    starts at 1300 px (was 1080), group labels hide between 1301 and 1560 px, item spacing slightly smaller.
14. **Bug found and fixed during the build.** The halves cache (`ConcurrentHashMap`) could not hold "no stream"; a race without a downloaded stream
    would have broken the page. Only found results are cached now; covered by `RaceServiceTest`.
15. **Table.** `race_goal` was created by `ddl-auto: update` in the local copy of the database (additive). The test goals entered for the
    screenshots were deleted again.

Findings on the owner's real data (for the owner, not a rule change):

- The predictions lean slow: 8 of 9 races were faster than predicted, median -6.7 %. Example: the current 5 km prediction is 20:30 and the
  second method (critical speed) says 20:45, while the last two 5 km races were 19:21 and 19:26. The cause is the one named in section 0 (fastest
  segments inside training runs are rarely maximal). With the goal 19:00 the page therefore says "binnen je bandbreedte" and, from his own history,
  "in 0 van de 49 periodes van 4 weken gebeurde zo'n verbetering". Both are shown as they are.
- Open points for the owner (none blocks approval): (1) a bias correction (the median error is large and stable enough to apply, but it changes
  results and was not asked for); (2) whether hilly runs that are not races should stop counting as anchors; (3) the three new race markings
  (two half marathons, one trail race) show up after the "Markeringen opnieuw ophalen" button has been pressed once after deploying.
