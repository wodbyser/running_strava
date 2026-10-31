# Garmin Phase 4 design: training load and recovery-adjusted analysis

Date: 2026-10-31. Status: DESIGN APPROVED by the owner 2026-10-31 (P4-1 to P4-12 answered in session, see section 9;
review notes for the start of the build in `GARMIN_PROGRESS.md`, open questions). BUILT 2026-10-31, AWAITING APPROVAL of the phase:
build notes and deviations in section 11, model validation report on real data in section 12. Algorithm version for everything below: `load-v1`.

Scope (roadmap Phase 4, confirmed by the owner in session 2026-10-31: "Hele lijst zoals roadmap"): backlog #5 training
response, #10 recovery time estimation, #11 early fatigue detection, #13 rest day effectiveness, #14 deload
effectiveness, #15 recovery trend forecast, #31 fatigue-adjusted performance, #33 internal training load, #34 external
training load, #35 acute load, #36 chronic load, #37 fitness-fatigue model, #38 monotony, #39 strain, #41 load
progression, #42 recovery-adjusted load, #43 workout effectiveness, #44 training frequency, #45 long run duration,
#47 detraining, #48 adaptation lag, #81 session-type readiness. Nothing else.

Basis: `GARMIN_API.md` sections 4.3 and 8, `GARMIN_ROADMAP.md` Phase 4, `docs/garmin-phase-2-design.md` (baselines,
bands, readiness), `docs/garmin-phase-3-design.md` (stream cleaning, session types, steady runs, EF, critical speed),
`docs/garmin-field-inventory.md`, `docs/ui-guidelines.md` (binding for every page).

Fact labels: VERIFIED (code, live data, or Python source), DOCUMENTED (Swagger text only), UNVERIFIED, DECISION
(owner), PROPOSAL (agent choice, owner may change). Questions for the owner: section 9 (P4-1 to P4-12).

## 0. Reading guide: what this phase can and cannot do

Phase 4 links three things for the first time: how much the owner trained (load), how his body reacted the next
days (Phase 2 recovery values) and how his running developed (Phase 3 performance values). Two warnings shape the
whole design:

1. **There is no outcome to train against.** The app does not know when the owner was "fatigued", "overreached" or
   "fitter" except through the same indicators it computes. So most items are **descriptive** (what happened after
   what, with counts), not predictive. Where an item does predict (#15 forecast, #37 fitness-fatigue model), it is
   shown only after it beats a naive baseline on held-out data (spec rule 10). Otherwise the page says it does not.
2. **Small samples.** About 22 months of heart-rate data, 6 breaks of 14 days or longer in all history, probably a
   handful of deload weeks. Several items (#14, #43, #47, #48) will often answer `onvoldoende data`. That is the
   correct answer, not a bug (rule 4).

No item says how much to train, that a load is safe, or that an injury will happen (backlog section C caution, rule 9).
The acute:chronic ratio is shown as a plain comparison, never as a risk zone.

## 1. Inputs and data census

### 1.1 Inputs (all already stored; no new Garmin endpoint, no schema change)

| Source | What | Used for | Status |
|---|---|---|---|
| Strava `activities` | sport, start (activity-local date), moving time, distance, elevation gain, average HR, `workout_type`, power fields | external load, fallback internal load | VERIFIED |
| Strava `activity_streams` | `time`, `distance`, `heartrate`, `grade_smooth` | internal load per sample, flat-equivalent distance | VERIFIED |
| Strava laps + Phase 3 code | `SessionClassifier`, long run definition (#26), `CleanRun`, `SteadyRun`, `EfIndex`, `CriticalSpeed`, `GradeAdjusted` | session type, performance indicators | VERIFIED, Phase 3 APPROVED |
| Phase 2 code | `Baseline`, `RecoveryAnalyzer`, `ReadinessAnalysis`, `Daily` norms | recovery state per day | VERIFIED, Phase 2 APPROVED |
| Garmin daily tables | HRV nightly average, resting HR, sleep, Body Battery, stress | recovery response | VERIFIED (Phase 1/2) |
| Garmin `garmin_hr_zones` | `maxHr`, `restingHr` (row `RUNNING`) | HR reference for internal load (P4-3) | VERIFIED present; how Garmin derives them UNVERIFIED |
| Garmin `garmin_activity_ref` | `activityTrainingLoad`, aerobic/anaerobic training effect | reference line and agreement check only, never mixed into the app load | Present on every activity; formula and unit UNVERIFIED |
| `strava_tokens.resting_hr` | manual resting HR | fallback for the HR reference | VERIFIED |

Not available: session RPE (no input exists; the "Training beoordelen" page only builds an AI prompt), power streams,
temperature (no `temp` stream in any run), Garmin Training Readiness / Training Status / Recovery Time / acute load
(spec 2.4: UNVERIFIED, not in the client).

### 1.2 Data census (2026-10-31, read-only counts on the production database, nothing committed)

| Item | Result |
|---|---|
| Runs (Run, TrailRun) | 534, 2024-05-01 to 2026-10-30, **all 534 with stored streams** now (the owner ran "Detaildata aanvullen") |
| Runs with average HR / HR stream | 480 of 534. 47 runs before 2024-10 have no HR; of the 487 runs from 2024-10 onwards 480 have HR and 7 have none (correction of the first census text, review note c) |
| Runs since Garmin data starts (2024-12-20) | 415, of which 413 with HR |
| Other sports (Strava) | Walk 38 (22 with HR), Ride 8, AlpineSki 6 (no HR), Swim 2, Rowing 1, Workout 1 |
| Garmin activities | 452, all with `activityTrainingLoad` and training effect (running 415, walking 24, cycling 8, other 5) |
| Garmin daily rows | HRV 629, resting HR 674, sleep 625, Body Battery 674, stress 681 days (2024-12-19 to 2026-10-30) |
| Strava `workout_type` on runs | 0: 238, 1 (race): 9, 2 (long run): 12, null: 275 |
| Days with two or more runs | 24 |
| Breaks between runs | longest 57 days; 10 breaks of 7 days or more, 6 of 14 days or more (all history) |
| Garmin zone row `RUNNING` | `maxHr`, `restingHr`, `lactateThresholdHr` present |

Consequences:

- **Internal (HR) load starts 2024-10.** Earlier weeks get external load only and are drawn as "geen hartslag".
- **The chronic load and the fitness-fatigue model need a run-in.** An exponential average with a 42-day time
  constant needs about 3 time constants (126 days) before the start value no longer matters. Values before about
  2025-02 are shown greyed as "opbouwperiode" and are excluded from every validation and comparison.
- **Recovery response analyses (#5, #10, #13, #14, #15, #42, #44, #45, #81) start 2024-12-20** (first Garmin day).
- Detraining (#47) has at most 6 candidate breaks in all history, fewer with HR and Garmin data on both sides.

## 2. Common rules

### 2.1 Days, weeks, time

- Day = activity-local calendar date (Phase A Q4, `ActivityTime`); Garmin rows use their own `calendarDate`
  (HRV, resting HR and sleep belong to the **wake-up date**, so "the morning after a session on day D" is the Garmin row
  of date D+1). Week = Monday to Sunday (`WeeklyVolume` convention). "Today" = Europe/Brussels.
- A day without any activity has load 0 (a real zero, not missing). A day **before the first activity with HR** has
  internal load "unknown", never 0.
- Today's load is `lopend` (incomplete) and never part of a baseline, a validation set or a "previous period".

### 2.2 Session classes (shared by #5, #10, #43, #45, #81)

Built from Phase 3 code, no new classifier:

| Class (UI) | Rule |
|---|---|
| `Rustig` (easy) | `SessionType` STEADY or STRIDES and not a long run |
| `Lange duurloop` | long run definition of Phase 3 section 3.8 (Strava `workout_type` 2, or at least 75 min and at least 1.5 x the 90-day median run duration) |
| `Tempo` | `SessionType` TEMPO |
| `Interval` | `SessionType` INTERVAL |
| `Wedstrijd` | `SessionType` RACE |
| `Andere sport` | non-running activity (walk, ride, swim, ...), only when included (P4-4) |

All classes are "automatisch herkend" (Phase 3 rule). A day with more than one session gets the class of its hardest
session (order: race, interval, tempo, long, easy), and is flagged `meerdere sessies`.

"Hard session" (used in several items) = class Tempo, Interval or Wedstrijd, or an internal load above the owner's
own 90th percentile of single-session load over the previous 180 days. PROPOSAL.

### 2.3 Baselines, bands and trends

Reused unchanged from Phase 2: personal baseline = median and robust SD (1.4826 x MAD, floor 1.0) of the window
**before** the evaluated day, 70 % minimum data rule, bands `binnen eigen norm` (|z| < 1), `afwijkend` (1 to 2),
`sterk afwijkend` (>= 2). Both directions are reported; colours mean "distance from your own norm", not good or bad.

Group comparisons ("after interval sessions HRV was ...") use the **median of the per-session deviations** with a
95 % interval from a percentile bootstrap (2000 resamples, fixed seed so the page is reproducible). A difference is
called a difference only when the interval excludes 0 **and** the group has at least the minimum n (2.5). Otherwise:
`geen duidelijk verschil` or `onvoldoende data`. PROPOSAL.

### 2.4 Confounders and "isolated" sessions

A recovery response to one session is contaminated by the next session and by poor sleep. Rules (PROPOSAL):

- **Isolated session:** no other hard session (2.2) in the 2 days before and the follow-up window after it
  (default 3 days). Easy sessions inside the window are allowed but counted and shown ("2 rustige runs in het
  venster"). Only isolated sessions enter #5, #10, #45 statistics; all sessions are listed.
- Sleep is shown, not corrected: the median sleep duration of the follow-up nights is shown per group so the owner
  can see if one group simply slept less.
- Days with Garmin data missing for a value are dropped for that value only (no imputation).
- Every relationship carries the Phase 3 note: "Verband, geen oorzaak. Slaap, ziekte, stress, warmte en andere
  sessies beinvloeden deze waarden."

### 2.5 Minimum data (rule 4, abstain with counts)

| Item | Minimum |
|---|---|
| #33 internal load per session | HR coverage of at least 80 % of moving time (stream) or an average HR (fallback, flagged) |
| #35, #36 acute and chronic load | internal load known for at least 70 % of the days in the window |
| #37 fitness-fatigue model | 180 days of internal load after the run-in, 30 performance points for fitting, validation per 3.4 |
| #38, #39 monotony and strain | 7 complete days of a week, internal load known on all 7 |
| #41 progression | 4 complete weeks before the week shown |
| #5, #10 response per class | 8 isolated sessions in the class with the morning value present |
| #13 rest days | 15 rest days and 15 training days with values on both mornings |
| #14 deload | 3 detected deload weeks for a summary; 1 or 2 are listed without a summary |
| #15 forecast | 120 days of history and a passed held-out check (3.12) |
| #31 fatigue-adjusted EF | 30 steady runs with EF and a known prior load; 8 per group |
| #43 workout effectiveness | 12 complete 4-week blocks with performance data on both sides |
| #44 frequency | 6 weeks per frequency group |
| #45 long run duration | 5 isolated long runs per duration group |
| #47 detraining | 1 episode for a list, 3 for a summary |
| #48 adaptation lag | 52 weeks of weekly load and weekly EF; permutation check (3.20) |
| #11 fatigue signals | each signal has its own minimum (from Phase 2 and 3) |
| #81 readiness per type | at least 2 of its components with data |

All values are PROPOSALS and config values (`garmin.load.*`), labelled UNVERIFIED (no external validation).

### 2.6 Labels and wording

- `[gemeten]` stored values (duration, distance, Garmin load), `[berekend]` sums, medians and differences,
  `[app-schatting]` TRIMP, model outputs, bands, signals, forecasts, readiness, `[onbeschikbaar]` with the reason.
- Garmin's own `activityTrainingLoad` is always labelled "Garmin-belasting (Garmin-schatting, formule onbekend)" and is
  **never** added to, averaged with or replaced by the app load (spec: never average incompatible measurements).
- Safety line on every page with a recovery or readiness statement (Phase 2 section 2.3, unchanged): "Geen medische
  beoordeling. Pijn of klachten wegen zwaarder dan elke score. Normale waarden zijn geen bewijs dat een blessure of
  ziekte hersteld is."
- Words never used: "veilig", "klaar om te trainen", "overtraind", "blessurerisico", "optimaal" (the backlog titles
  #44 and #45 say "optimal"; the UI uses "Trainingsfrequentie en reactie" and "Lengte van je lange duurloop").

## 3. Metrics

### 3.1 #33 Internal training load (TRIMP)

**Model choice (P4-1):** Banister's training impulse (TRIMP), computed **per sample** from the HR stream. Reasons:
every run since 2024-10 has an HR stream at about 1 Hz (census); session RPE does not exist; Garmin's load is a black
box (UNVERIFIED) and may only serve as a cross-check; Banister TRIMP is the classic input of the fitness-fatigue model
(#37), so the two fit together. Edwards' zone TRIMP is the documented alternative (P4-1).

- **Formula (Banister 1991):** per valid sample `dTRIMP = (dt / 60) x x x 0.64 x e^(b x x)` with
  `x = (HR - HRrest) / (HRmax - HRrest)` clamped to 0..1 and `b = 1.92` (coefficient published for men; DECISION P4-2, 2026-10-31). Session TRIMP = sum over valid samples (`CleanRun` rules: no interpolation over gaps > 30 s, HR 40 to 230).
  Hand check: 60 min at x = 0.6 gives `60 x 0.6 x 0.64 x e^(1.152) = 60 x 0.6 x 0.64 x 3.1645 = 72.9`.
- **Unlike Phase 3, standing samples count** (HR during a stop is still load). Samples with speed below 0.5 m/s are kept
  for TRIMP; only HR validity and gaps decide.
- **HR reference (P4-3):** one fixed pair for the whole history: `HRmax` and `HRrest` from the Garmin `RUNNING` zone row;
  fallback `HRmax` = app 12-month estimate (`HrZones.estimateMaxHrForRuns`) and `HRrest` = Garmin 28-day resting HR median
  of today, then the manual value. Fixed values keep old loads stable when the reference moves; the page shows the pair
  and its source ("huidige referentie toegepast op alle runs", as Phase 3 does for zones).
- **Fallback without a usable stream** (HR coverage below 80 %): `TRIMP = moving minutes x x(avgHR) x 0.64 x e^(b x x(avgHR))`,
  flagged `geschat uit gemiddelde hartslag`. Because e^(bx) is convex this underestimates intervals; the share of
  fallback sessions is shown per period.
- **Other sports:** P4-4. Default: activities with HR from every sport count, with the same formula (HR is systemic
  load); the charts split `lopen` and `andere sport`. Activities without HR (ski, most old walks) get no internal load
  and are listed as `zonder hartslag`.
- **Garmin cross-check (`[berekend]`):** for runs matched to a Garmin activity (Phase 1 matcher, MATCHED only),
  Spearman rank correlation between app TRIMP and Garmin `activityTrainingLoad`, with n. Shown under "Meer uitleg" as an
  agreement check, not as validation of either.

### 3.2 #34 External training load

Per session and per day/week, all `[gemeten]` or `[berekend]`, never combined into one number:

- moving time, distance, elevation gain (Strava);
- **flat-equivalent distance** = distance with the Minetti factor of Phase 3 section 3.12 per sample (grade beyond
  +/- 20 % ignored; runs failing the GAP plausibility rule fall back to plain distance, flagged);
- **hard minutes** = time in zone 4 and 5 (Phase 3 zone set, P3-1);
- average power x duration (kJ) only for runs with `device_watts = true`, shown as an extra column, no trend
  (power is a device estimate, Phase 3).

### 3.3 #35 Acute and #36 chronic load

Two families, shown with different names so they are never confused (P4-5):

| Name in UI | Definition | Default window |
|---|---|---|
| `Belasting laatste 7 dagen` (#35) | sum of daily TRIMP of the 7 days ending yesterday (today `lopend` shown apart) | 7 days |
| `Gemiddelde week (laatste 4 weken)` (#36) | sum of daily TRIMP of the 28 days ending yesterday / 4 | 28 days |
| `Korte-termijnbelasting` (model, #37) | exponentially weighted average of daily TRIMP, time constant 7 days | 7 |
| `Lange-termijnbelasting` (model, #37) | same, time constant 42 days | 42 |

- The rolling family is the main view (plain, explainable). The exponential family belongs to the model section.
- **Comparison, no zones:** the page shows "deze 7 dagen: x % boven / onder je gemiddelde week van de laatste 4 weken".
  The ratio is not coloured by any risk zone and no "sweet spot" (for example 0.8 to 1.3) is shown, because the
  acute:chronic ratio is not a validated injury predictor (backlog caution). Colours use the owner's own history only
  (2.3: is this week's load within his own band of weekly loads of the last 26 weeks).
- Same windows on distance and time (external), so the owner sees whether load rose through volume or intensity.

### 3.4 #37 Fitness-fatigue model (validate before use)

**Model:** Banister impulse-response: `p(t) = p0 + k1 x F(t) - k2 x A(t)` where `F` and `A` are the 42-day and 7-day
exponential loads (3.3) up to the day before `t`. Time constants fixed at 42 and 7 (literature defaults; fitting them
on this little data overfits; PROPOSAL). `p0, k1, k2` are fitted by least squares on performance points.

**Performance points (the hard part):** the app has no maximal test. Proposal: the EF of the steady-run window of each
steady run (Phase 3 #16), in the zone group with most runs, expressed as % of the owner's median EF. Critical speed
(#22) changes too rarely and needs too many anchors to give enough points. Races (9) are too few. PROPOSAL, P4-6.

**Validation (rule 10, temporal, walk-forward):**

1. Exclude the run-in (1.2). Order points by date.
2. For each point after the first 180 days of usable load and 30 earlier points: fit `p0, k1, k2` on **earlier points
   only**, predict this point. Collect the errors.
3. Compare with two naive forecasts on the same points: (a) the median EF of the previous 28 days, (b) the previous
   point.
4. Report: mean absolute error of the model and of both baselines, the share of points where the model is closer,
   and the stability of `k1` and `k2` (their range over the walk-forward fits; a sign change means unstable).

**Use rule:** the model's performance curve ("vorm") is shown **only** when its walk-forward error is at least 10 %
lower than the better naive baseline **and** `k1`, `k2` stay positive in at least 80 % of the fits (PROPOSAL). Otherwise
the section shows the two load lines as plain load summaries ("korte- en lange-termijnbelasting") with the sentence
"Het model voorspelde je efficiëntie niet beter dan 'zoals de laatste 4 weken' (fout x tegen y), dus de app toont geen
vorm- of fitheidscijfer." No "form = fitness - fatigue" number (TSB) is shown in either case: it has no validated
meaning on its own (rule 6).

The validation result (numbers, dates, n) is part of the Phase 4 model validation report (exit criterion), section 8.

### 3.5 #38 Training monotony and #39 strain

- **Monotony (Foster 1998):** per complete week (Monday to Sunday) `mean(daily TRIMP) / SD(daily TRIMP)` over 7 days,
  rest days counted as 0. Population SD. If SD = 0 (seven identical days, or seven rest days) monotony is
  `onbeschikbaar (geen variatie)`, not infinite.
- **Strain:** `weekly TRIMP x monotony`.
- Hand check: days 0, 50, 0, 80, 0, 120, 0 give mean 35.71, population SD 45.31, monotony 0.788, strain
  250 x 0.788 = 197.1.
- **No external thresholds.** Foster's "monotony above 2" warning comes from a different load scale (session RPE) and
  is not shown as a limit. Weeks are coloured against the owner's own 26-week band (2.3).
- Chart: weekly bars of load with monotony as a line; table folded away.

### 3.6 #41 Training load progression

- Week-over-week change of weekly TRIMP, distance and time (%), and this week against the mean of the 4 weeks before.
- **Own history instead of the 10 % rule:** for each week, where its change ranks among the owner's own week-over-week
  changes of the last 52 weeks ("de grootste stijging in 6 maanden"). The 10 % rule is not shown (not evidence based,
  UNVERIFIED).
- Weeks after a break (previous 4-week mean below 30 % of the chronic level) are marked `na een onderbreking`, because
  any restart is a big percentage.
- Chart: weekly load with the 4-week mean line, bars coloured by the owner's own band.

### 3.7 #42 Recovery-adjusted load

A shared daily timeline, not a combined number:

- top: daily internal load (bars) with the 7-day and 4-week lines;
- below: the Phase 2 readiness level per morning (the four components, colour by band), plus sleep shortfall;
- conclusion text from two independent facts: load level of the last 7 days against the owner's own weekly band
  (`laag`, `gewoon`, `hoog`) and the Phase 2 readiness of today. Example: "Je belasting van de laatste 7 dagen is hoog
  voor jou (x % boven je gemiddelde week), en 2 van 4 herstelwaarden wijken af (rusthartslag hoger, HRV lager)."
- Never: "je moet rusten", "veilig". Rule 5 note.

### 3.8 #5 Training response analysis

Question: how do HRV, resting HR, sleep duration and Body Battery at waking react in the mornings after a session, per
session class?

- For each isolated session on day D (2.4) and each morning `D+1 ... D+k` (k = 3 default): deviation `z` of each value
  against its 28-day baseline **ending before D** (so the session's own aftermath does not move the reference).
- Per class and morning: median `z` with bootstrap interval and n (2.3), and the raw median difference in the metric's
  unit (for example "-4 ms", "+2 bpm").
- Comparison row: the same statistic for **rest days** (no run on D, see 3.10) as a reference for "nothing happened".
- Chart: small multiples (one per metric), x = morning after (1, 2, 3), one line per session class, band for the
  interval, rest-day line dashed.
- Wording: "Na intervaltrainingen lag je HRV de tweede ochtend gemiddeld 0.8 SD lager dan je norm (n = 14, interval
  -1.2 tot -0.4). Verband, geen oorzaak."

### 3.9 #10 Recovery time estimation

From #5, per class: the first morning `m` (1 to 5, k extended to 5 for this item) from which the median deviation of
**both** HRV and resting HR has an interval that includes 0 and |median z| < 0.5, and stays so the next morning.
Output: "typisch terug binnen je norm na x ochtenden (n sessies)". If never reached within 5 mornings: "langer dan 5
dagen of niet meetbaar". If at morning 1 already within: "geen meetbare afwijking".

- This is the owner's own typical pattern, not Garmin's "Recovery Time" and not a prescription. The page says so.
- Minimum: 8 isolated sessions in the class with both values (2.5). Sessions with a second hard session within 5 days
  are not isolated for this item.

### 3.10 #13 Rest day effectiveness

- **Rest day (DECISION, owner 2026-10-31):** a day without a run. Walks and other sports are allowed; such days are
  flagged (`met andere activiteit`) so the owner can tell them apart from days without any activity.
- Pairs `(D, D+1)` where D is a rest day: change from the morning of D to the morning of D+1 in HRV, resting HR, Body
  Battery at waking (in units and z). Compared with pairs where D was an easy-run day, and where D was a hard-session day.
- Also: one rest day against two consecutive rest days (change over the second rest day), when n allows.
- Output: median change per group with interval and n; no verdict beyond `duidelijk verschil` / `geen duidelijk verschil`.
- Confounder shown: median sleep of the night after D per group.

### 3.11 #14 Deload effectiveness

- **Deload week (detected, PROPOSAL, P4-10):** a complete week whose TRIMP is at most 70 % of the mean of the 3 weeks
  before it, while those 3 weeks were each at least 80 % of the 4-week chronic level (so a break after illness or a
  holiday start is not a deload). Weeks that start a break (0 runs) are not deloads; they belong to #47.
- Per deload: recovery components in the last 3 days of the deload week against the last 3 days of the week before
  (z per component), and steady-run EF in the 14 days after against the 28 days before (Phase 3 rules, n shown).
- 1 or 2 deloads: listed with their dates and values, no summary. 3 or more: median change with n. Detected weeks are
  listed so the owner can see if the detection matches his own memory (he may mark a week as not a deload: P4-10).

### 3.12 #15 Recovery trend forecast

Cautious short-term projection of the **7-day median** of HRV and of resting HR, 1 to 3 days ahead.

- Model: the deviation from the 60-day baseline decays toward 0 with a fitted persistence coefficient (AR(1) on daily
  deviations), fitted walk-forward on past data only.
- Baseline forecast: "zoals vandaag" (persistence).
- **Use rule:** shown only when over the last 120 days of walk-forward forecasts its mean absolute error is at least 10 %
  lower than persistence (PROPOSAL). Then: a range (the 10th to 90th percentile of past errors around the forecast), not
  a point. Otherwise: "Een voorspelling was in het verleden niet beter dan 'zoals vandaag', dus de app toont er geen."
- It does not use planned training (the app does not know the plan). The text says so.

### 3.13 #31 Fatigue-adjusted performance

Question: is the owner's efficiency lower on steady runs done while carrying more recent load or with recovery
values outside his norm?

- Per steady run (Phase 3 eligibility): EF of the window as % of the owner's rolling 90-day median EF before the run
  (removes slow fitness change), the load of the 3 days before the run as % of his chronic daily load, and the readiness
  components of that morning.
- Groups: prior 3-day load `laag` / `gewoon` / `hoog` (own tertiles over the analysis period), and readiness `binnen norm`
  / `1 afwijkend` / `2 of meer afwijkend`. Median relative EF per group with interval and n.
- Output: "Op stabiele runs na een zware 3 dagen was je efficiëntie gemiddeld x % lager dan je eigen niveau (n = 12)".
- No corrected "fatigue-free EF" number in v1 (the groups are the adjustment). Rule 3: only within one EF zone group.

### 3.14 #43 Workout effectiveness

Question: are 4-week blocks with more of a session class followed by better performance?

- Blocks: consecutive 4-week blocks (Monday aligned). Per block: number and share of interval, tempo and long sessions,
  total TRIMP. Outcome: change of median steady-run EF between this block and the next block (Phase 3 EF, same zone
  group), and the critical speed change when both fits exist.
- Analysis: Spearman correlation between the share of a class and the next-block EF change, with a permutation test
  (shuffle outcomes 2000 times) and n blocks. Also the plain table of blocks.
- Confounder: total load is shown next to the share; a class share correlating with total load is flagged ("meer
  intervallen viel samen met meer totale belasting; het effect is niet te scheiden").
- Expect `onvoldoende data` or `geen duidelijk verband` for a long time (about 20 usable blocks). Wording: "verband, geen
  oorzaak", no advice.

### 3.15 #44 Training frequency and response

- Complete weeks grouped by runs per week (1 to 2, 3, 4, 5 or more; groups merged when below the minimum).
- Per group: median recovery deviation over the following week's mornings (HRV, resting HR), and median weekly TRIMP and
  distance (so frequency and volume can be told apart; when they move together the page says so).
- Optional performance outcome: EF change over the next 4 weeks (as #43).
- Title: "Trainingsfrequentie en reactie". No "optimal" frequency is computed.

### 3.16 #45 Long run duration

- Isolated long runs (2.4) grouped by moving time: 75 to 90, 90 to 105, 105 to 120, more than 120 minutes (PROPOSAL).
- Per group: recovery response as #5 (mornings 1 to 3) and the Phase 3 long-run EF change between first and last third.
- Output: one small chart per metric with the groups side by side, n per group. No "best length". Title: "Lengte van je
  lange duurloop".

### 3.17 #47 Detraining analysis

- **Episode (PROPOSAL):** a period of at least 14 days with weekly TRIMP below 30 % of the chronic level before it, or no
  run at all. From the census: at most 6 candidate breaks in all history.
- Per episode: steady-run EF (median of the last 28 days before against the first 28 days after; abstain per side
  below 3 runs), critical speed before and after when both fits exist, resting HR and HRV 7-day medians in the last week
  before against the last week of the episode.
- Listed per episode with dates and n. Summary only with 3 or more usable episodes. The cause (injury, illness, holiday)
  is unknown to the app and not guessed.

### 3.18 #48 Training adaptation lag

- Weekly series (complete weeks after the run-in): change in 4-week mean TRIMP and the weekly median steady-run EF.
- Cross-correlation of load change with EF change at lags 0 to 8 weeks. A lag is reported only when its correlation
  exceeds the 95th percentile of the same statistic on 2000 block-permuted series (blocks of 4 weeks, to keep
  autocorrelation) **and** at least 52 weeks are usable.
- Otherwise: "geen meetbare vertraging gevonden". Expected for a long time. Wording as #43.

### 3.19 #11 Early fatigue detection (signals, no diagnosis)

A list of independent **signals**, each from an existing rule, shown side by side. No combined score.

| Signal | Rule (source) |
|---|---|
| HRV aanhoudend lager | Phase 2 sustained rule (5 of 7 days below, \|z\| >= 1) |
| Rusthartslag aanhoudend hoger | Phase 2 sustained rule |
| Slaaptekort | Phase 2 7-night sleep target check `afwijkend` or worse |
| Efficiëntie lager | median relative EF (3.13) of the steady runs of the last 14 days below -3 % with at least 3 runs |
| Belasting hoog voor jou | 7-day load above the owner's own 90th percentile of 7-day loads (last 26 weeks) |
| Monotonie hoog voor jou | last complete week's monotony above the owner's own 90th percentile (last 26 weeks) |

- Page text: "x van 6 signalen actief" with each signal, its value and its rule. Signals without data say so.
- **Base rate (validation without an outcome):** for each count of active signals, how many days of the owner's history
  had that many (for example "3 of meer signalen tegelijk: 11 dagen in de laatste 12 maanden"). This shows how unusual
  the current state is; it is not a probability of anything.
- Wording: "signalen die samen kunnen wijzen op oplopende vermoeidheid". Never "je bent overtraind" or "ziek". Safety
  line (2.6).

### 3.20 #81 Session-type readiness

Per session type: Rustig, Lange duurloop, Tempo, Interval, Wedstrijdtempo. For each, a short list of **components**
marked `pleit ervoor`, `pleit ertegen` or `onbekend`, and one level. No number.

Components (all from the items above, PROPOSAL):

| Component | `pleit ertegen` when | Applies to |
|---|---|---|
| Herstel vandaag (Phase 2 readiness) | 2 or more components `afwijkend`, or any `sterk afwijkend` | all types; for Rustig only `sterk afwijkend` counts |
| Laatste harde sessie | fewer days ago than the owner's own recovery time for that class (#10); when #10 abstains, fewer than 2 days | Tempo, Interval, Wedstrijdtempo |
| Laatste lange duurloop | fewer days ago than the own long-run recovery time (#10); fallback 3 days | Lange duurloop |
| Belasting laatste 7 dagen | above the own 90th percentile (3.19) | all except Rustig |
| Slaap laatste nacht | more than 60 min under the owner's target | Tempo, Interval, Wedstrijdtempo, Lange duurloop |
| Vermoeidheidssignalen (#11) | 3 or more active | all |

Levels (like the Phase 2 hero card, no score): `Geen tegensignalen`, `Eén tegensignaal`, `Meerdere tegensignalen`,
`Onvoldoende data` (fewer than 2 components with data). Each level shows the components that caused it.

Example (backlog): "Interval: meerdere tegensignalen (rusthartslag 4 bpm boven je norm, HRV onder je 28-dagennorm,
2 harde sessies in 4 dagen). Rustig: geen tegensignalen."

- Garmin Training Readiness is not used (UNAVAILABLE). Pain and symptoms outweigh this (safety line). The text never
  says "klaar" or "veilig"; it says what the data shows.

## 4. Pages (follow `docs/ui-guidelines.md` from the first version)

Proposal (P4-7): one new nav entry **"Belasting"** with two pages, plus one card on `/recovery`.

### 4.1 `/load` "Belasting" (state now and evolution)

Order (owner may change):

1. **Conclusion card:** this week's load against the owner's own norm, today's recovery (Phase 2 level), active fatigue
   signals (#11), in two or three plain Dutch sentences. Hero style like `/recovery`.
2. **Welke training past vandaag?** (#81): five compact rows, one per type, level pill and the deciding components.
3. **Belasting over tijd** (#33, #35, #36): daily bars, 7-day and 4-week lines, own band; split lopen / andere sport;
   pre-HR period greyed. Tiles: last 7 days, average week, change in %.
4. **Opbouw per week** (#41, #34): weekly TRIMP, km, hours, hard minutes; change ranked against own history.
5. **Belasting en herstel** (#42): shared timeline of load and readiness.
6. **Vermoeidheidssignalen** (#11): six signals with their values and the base rate.
7. **Afwisseling** (#38, #39): monotony and strain per week against own band.
8. **Fitheid-vermoeidheidsmodel** (#37): validation result first, then the curve only if it passed.
9. **Herstelverwachting** (#15): only if it passed its check, else one sentence.
10. **Welke activiteiten tellen mee?** (coverage: sessions with stream TRIMP, fallback, without HR, other sports, HR reference
    and its source, Garmin cross-check).
11. **Begrippen** (`LoadGlossary`: belasting/TRIMP, korte- en lange-termijnbelasting, monotonie, strain, eigen norm,
    deload, tegensignaal, bootstrap-interval as "onzekerheidsmarge").

### 4.2 `/load/response` "Reactie op training" (what the history shows)

1. Conclusion: the two or three clearest findings with n (only differences that pass 2.3), else "nog geen duidelijke
   patronen".
2. **Reactie per type training** (#5) and **Hersteltijd** (#10).
3. **Rustdagen** (#13).
4. **Rustweken** (#14) with the list of detected weeks.
5. **Lengte van je lange duurloop** (#45).
6. **Efficiëntie en vermoeidheid** (#31).
7. **Trainingsfrequentie en reactie** (#44).
8. **Wat bracht verbetering?** (#43) and **Vertraging** (#48).
9. **Na een onderbreking** (#47).
10. Begrippen.

### 4.3 Common UI rules

- Every window adjustable with the default shown (`LoadWindows` via `fragments :: windowForm`): acute 7, chronic 28,
  chart 180 days, own band 26 weeks, follow-up mornings 3, isolation days 2, recovery-time horizon 5, deload threshold
  70 %, detraining 14 days, signal windows. Minimum counts scale with the window (70 % rule).
- Dutch dates via `PerfDates`, run names next to dates, clickable points to `/activity/{id}`, gaps broken in lines.
- `/recovery` gets one link card "Welke training past vandaag? → Belasting" (no other change to Phase 2 pages).
- Checked in headless Chrome on real data on a second instance (port 8091) before reporting.

## 5. Code layout (after design approval)

| Module | Item |
|---|---|
| core `com.running.load` (new, pure, no Spring) | `LoadAlgorithm.VERSION = "load-v1"`, `Trimp` (#33), `ExternalLoad` (#34), `DailyLoad` (day series, unknown vs zero), `LoadWindows` math (#35, #36), `FitnessFatigue` (#37 incl. walk-forward validation), `Monotony` (#38, #39), `Progression` (#41), `SessionClasses` (2.2), `Response` (#5, #10, #13, #45, bootstrap), `Deload` (#14), `RecoveryForecast` (#15), `FatigueAdjusted` (#31), `BlockEffect` (#43, #44, permutation), `Detraining` (#47), `AdaptationLag` (#48), `FatigueSignals` (#11), `SessionReadiness` (#81) |
| core reuse | Phase 2 `Baseline`, `RecoveryAnalyzer`, `ReadinessAnalysis`; Phase 3 `CleanRun`, `SessionClassifier`, long run rule, `EfIndex`, `CriticalSpeed`, `GradeAdjusted`, zones. No change to their results |
| runner `analysis/LoadService` | loads activities, streams (reuses `PerformanceService`'s per-run cache where possible), Garmin rows; calls core; in-memory cache keyed by data version and `load-v1` |
| runner `analysis/LoadText`, `LoadGlossary`, `LoadWindows` | view models and Dutch text |
| runner `frontend/LoadController` | `GET /load`, `GET /load/response`, `GET /load/data` (JSON). All read-only; `NoStateChangingGetTest` extended |
| templates | `load.html`, `load-response.html`, nav entry "Belasting", link card on `recovery.html` |
| config | `GarminProperties.load` (`garmin.load.*`): TRIMP coefficient, HR reference source, sports included, windows, minimum counts, model use thresholds. Defaults = this document |

No new table, no schema change, no Strava column change, no new Garmin call. Computed on demand. If the first `/load`
render on the production copy takes more than 5 s even with the cache, the build stops and the owner decides on a
persisted daily load table (same rule as P3-7).

Logging: counts and the algorithm version only; no HR, load, recovery value or activity name.

## 6. AI prompt

No change by default (P4-9). Load numbers are not Garmin health data, but #42, #81 and #11 combine them with Garmin
recovery values. If the owner wants a `<belasting>` block it goes behind an explicit option like `?herstel=true`, with a
guardrail line, and Garmin-derived parts only when `herstel=true` is also set.

## 7. Tests (offline, hand-computed, synthetic data only)

| Area | Cases |
|---|---|
| TRIMP (#33) | 60 min at x = 0.6 gives 72.9 (b = 1.92); b = 1.67 variant; x clamped at 0 and 1; gap over 30 s not interpolated; 3 s samples equal 1 s samples; stream below 80 % HR coverage uses the average-HR fallback and is flagged; activity without HR gives unknown, not 0; other sport included or excluded per config |
| Day series | rest day = 0; day before first HR activity = unknown; two sessions on one day summed; today `lopend` and excluded |
| Acute, chronic (#35, #36) | rolling sums by hand; 70 % known-days rule; EWMA with τ 7 and 42 by hand for 3 days; percentage comparison text; no ratio colour field in the output type |
| Model (#37) | synthetic performance generated from known `k1`, `k2` is recovered; walk-forward uses only earlier points (adding a later point changes no earlier prediction); model not better than naive -> curve hidden and the sentence shown; unstable k sign -> hidden; no TSB field in the output |
| Monotony, strain (#38, #39) | the hand check of 3.5 (0.788, 197.1); SD 0 gives unavailable; incomplete week excluded |
| Progression (#41) | week-over-week %, rank against own history, after-break flag |
| Response (#5, #10, #13, #45) | baseline ends before D; isolation rule (hard session inside the window excludes); bootstrap reproducible with the fixed seed; interval excluding 0 vs including 0; 7 vs 8 sessions abstain boundary; recovery time first stable morning, "geen meetbare afwijking", "langer dan 5 dagen" |
| Deload (#14) | detection boundaries 70 % / 71 %; after-break week not a deload; 2 deloads listed without summary, 3 summarised |
| Forecast (#15) | AR(1) recovers a known coefficient; shown only when beating persistence by 10 %; range from past errors |
| #31, #43, #44, #47, #48 | groups by hand; permutation p-value with fixed seed; confounded class share flagged; episodes detected at 14 days, not 13; lag reported only above the permutation threshold |
| Signals (#11) | each signal alone; base-rate counting; no score field |
| Readiness (#81) | each component alone flips the level for the types it applies to; Rustig ignores a single `afwijkend`; fallbacks when #10 abstains; fewer than 2 components -> `Onvoldoende data`; no "veilig"/"klaar" in any text |
| Service, controller, page | `LoadService` with fakes; pages render with data, without Garmin data, without HR; JSON has no key or token; `NoStateChangingGetTest` covers `LoadController`; log capture shows no values |

Existing Strava, Phase 2 and Phase 3 tests: none expected to change. If one must, old and new values go here and in the
progress log.

Temporal validation (rule 10): #37 and #15 are walk-forward with naive baselines (3.4, 3.12). All baselines and
groupings use only data before the evaluated day by construction; a test adds a future day and checks no earlier value
changes.

## 8. Exit criteria (filled in at the end of the build)

| Criterion | Status |
|---|---|
| Design doc approved | DONE (owner, 2026-10-31) |
| Each metric has normal, abstain and boundary tests | DONE: 100 new core tests (`core/src/test/.../load`) and 21 new runner tests; hand-computed values (TRIMP 72.9, monotony 0.788 and strain 197.1, EWMA, 70 % rule), 8 versus 7 and 15 versus 14 boundaries, 70 versus 71 % deload, 14 versus 13 day episodes, walk-forward and permutation checks |
| Sample counts and the "verband, geen oorzaak" note on every relationship | DONE: every cell shows its n, abstain texts name the minimum; the note is on every relationship section and in the hero of `/load/response` |
| Model validation report (#37 walk-forward, #15 forecast check, Garmin cross-check) on real data | DONE: section 12 |
| No health data, identifiers or coordinates in logs | DONE: `LoadServiceTest` captures the log (counts and version only); `LoadControllerTest` checks the JSON for key, token and coordinates |
| Existing tests pass unchanged | DONE: 452 tests, 0 failures. One existing test extended, none changed: `NoStateChangingGetTest` now also covers `LoadController` |
| Pages checked in a browser on real data | DONE: headless Chrome on a second instance (port 8091), `/load` and `/load/response` read in full, two layout fixes made afterwards (section 11). `/recovery` link card checked |
| Owner approval of the phase | NOT STARTED: AWAITING APPROVAL. The first-render speed question is answered (background warm-up, section 11, "Speed") |

## 9. Questions for the owner: ALL ANSWERED 2026-10-31 in session

Answers: P4-1 Banister TRIMP per sample; P4-2 men's coefficient `b = 1.92`; P4-3 fixed Garmin `RUNNING` zone values;
P4-4 other sports with HR included; P4-5 to P4-12 all defaults accepted. Design approved by the owner 2026-10-31.

| # | Question | Default if not answered |
|---|---|---|
| P4-1 | Internal load model | Banister TRIMP per sample from the HR stream; Edwards zone TRIMP and Garmin load not used as the main model (Garmin load only as a cross-check) |
| P4-2 | Banister uses a sex-specific coefficient (1.92 for men, 1.67 for women) | ANSWERED: 1.92 (men) |
| P4-3 | HR reference for TRIMP | Fixed: Garmin `RUNNING` zone max HR and resting HR, applied to all history; fallback app max HR estimate and Garmin 28-day resting HR |
| P4-4 | Other sports (walks, rides, swims) in the internal load | Included when they have HR, shown separately; external load (km) running only |
| P4-5 | Acute 7 days and chronic 4 weeks (rolling) as the main view; exponential 7/42 only in the model section; no ratio risk zones | Accept |
| P4-6 | Model performance measure = steady-run EF; model curve shown only if it beats the naive forecast by 10 % in walk-forward validation; no "vorm"/TSB number | Accept |
| P4-7 | Pages `/load` "Belasting" and `/load/response` "Reactie op training", one nav entry, link card on `/recovery`; section order of section 4 | Accept |
| P4-8 | Session classes (2.2) and the hard-session rule (class or own 90th percentile of load) | Accept |
| P4-9 | AI prompt | No change |
| P4-10 | Deload detection (70 % of the 3 weeks before, those weeks at least 80 % of normal) and detraining (14 days below 30 %); no manual marking of weeks in v1 | Accept |
| P4-11 | Isolation rule (no other hard session 2 days before and during the 3 follow-up days) and the minimum n per item (2.5) | Accept |
| P4-12 | #81 components, thresholds and the four levels without a number | Accept |

## 10. Not in Phase 4

Race prediction and readiness (#49 to #59, Phase 5); injury module and load spike detection as injury warning (#60 to
#68, Later); illness detection (#12, Later); training responder analysis and other section F items (Later); running
economy (#20); session RPE input; FIT decoding or storage; any new Garmin endpoint call; any external AI call; any schema
change unless the owner approves it after the 5 s rule in section 5.

## 11. Build notes and deviations (2026-10-31)

Built as designed (pages `/load` and `/load/response`, JSON `/load/data`, `load-v1`, no schema change, no new Garmin call, no AI prompt change).
What the build had to decide or change, so the owner can overrule it:

| # | Item | Design said | Built | Why |
|---|---|---|---|---|
| 1 | Long run (class `Lange duurloop`) | Phase 3 rule with the median of the last 90 days | Same rule, but the median of the runs in the 90 days **before each run** (at least 5 runs, else only Strava `workout_type` 2) | Today's median would reclassify old runs; the class of a run must not change when time passes |
| 2 | After-break week (#41) | previous 4-week mean below 30 % of the chronic level | the previous week below 30 % of the mean of the 4 weeks before that week (at least 3 of them known) | "chronic level" was not defined for a single week |
| 3 | Critical speed in #43 and #47 | "and the critical speed change when both fits exist" | **Not built**: only the efficiency (EF) outcome | The critical speed needs a 90-day window of anchors; per 4-week block and per episode there are almost never two fits (design 3.14, 3.17 expect `onvoldoende data` anyway). Add later if wanted |
| 4 | Readiness per morning for history (#31, #42, base rate of #11) | "Phase 2 readiness" | **Own norm only** (28-day median and robust SD of HRV, resting HR, sleep duration, Body Battery at waking; 3 of 4 needed); the sleep **target** check is used for today only (real Phase 2 report) | Running the full Phase 2 analysis for 365 days per request is heavy; the sleep target check adds one more component for today |
| 5 | #5 rest-day reference | listed in 3.8, not in the code layout | built (`ResponseAnalyzer.restReference`): days without a run and without a hard session in the isolation and follow-up windows | design 3.8 asks for it; it also shows a structural offset (see section 12) |
| 6 | Chart points with fewer than 3 sessions | not specified | not plotted (they stay in the tables with their n) | one or two sessions made charts unreadable (Body Battery at -14 SD) |
| 7 | #44 "following week" | not specified | the 7 mornings Monday to Sunday of the week after the week counted; at least 4 values; weekly median first, then the group median; groups under 6 weeks are merged upward | |
| 8 | Performance points for #14, #31, #37, #43, #47, #48 | steady-run EF in the zone group with most runs | zone group 1-2 (218 steady runs), EF as % of the median EF of that group, taken from the Phase 3 `PerformanceService` report | reuse of Phase 3 per-run cache |
| 9 | Hero of `/load` | conclusion in two or three sentences | headline from the number of deviations (load not "gewoon voor jou", 2 or more recovery values or a strong deviation, 3 or more fatigue signals): 0 = `hero-ok`, 1 = `hero-warn`, 2 or more = `hero-alert`; colours mean distance from the own norm | needed a rule; the legend says so |
| 10 | Acute 7 days "own norm" | band of the owner's weekly loads | the median and robust SD of the trailing 7-day sums of the last 26 weeks (a 28-value minimum); the 90th percentile of the same values for the signal and for #81 | one definition for the tile, the signal and #81 |
| 11 | `Insufficient` count in #10 | n of isolated sessions | the largest number of isolated sessions with **both** HRV and resting HR on one morning | the first version said "17 sessions" while only 7 had both values |
| 12 | Cache | by data version and `load-v1` | per activity (id, HR reference, zone floors, version): TRIMP, hard minutes, flat-equivalent distance; the rest is recomputed per request | a changed old activity is only recomputed after a restart (same limit as Phase 3) |
| 13 | Power x duration (kJ), #34 | extra column, no trend | a column "Vermogen (kJ, apparaat)" in the folded week table, shown only when a week has a run with a device power meter (none in the current data, so it is not visible yet) | as designed; no trend |

Other facts of the build:

- `HardSessions`: a non-class session counts as hard when its load is above the own 90th percentile of the previous 180 days **and** at least 10 earlier sessions exist.
- Today is never in a window, a baseline or a validation set; its load is shown apart as `lopend`.
- Activities that are manual or flagged on Strava are left out, as in Phase 3.
- Config keys `garmin.load.*` (`GarminProperties.Load`): TRIMP coefficient, other sports, minimum counts, model gain. All periods are URL parameters (`LoadWindowSettings`).
- Existing Phase 2 and Phase 3 results are unchanged (no test needed a new value).

### Speed (decision for the owner)

Design section 5 says: if the first `/load` render on the production copy takes more than 5 s, stop and let the owner decide about a persisted daily load table.
Measured on the production data (534 runs, 590 activities with streams):

| Moment | Time |
|---|---|
| First request after the app started (nothing cached), `/load` or `/load/response` | **15 to 17 s** (of which about 6 s is the Phase 3 per-run cache that `/performance` also needs on its first request) |
| Every later request of either page | **1.4 to 1.8 s** (the shared base 1.2 s, the page itself 0.4 s) |

No table was added. The owner chose option B in session on 2026-10-31 (the options were: A accept one slow first request, B warm the caches in the background right after the app starts, C persist daily load in a new table). Built: `LoadService.warmUpInBackground` (daemon thread after `ApplicationReadyEvent`, config `garmin.load.warm-up`, default true, failures are swallowed and logged as the exception class only). Measured after the change: the first `/load` request after start-up 2.0 s, `/load/response` 1.7 s (the warm-up itself takes about 13 s in the background and also fills the Phase 3 cache). No schema change.

## 12. Model validation report on real data (2026-10-31, production copy, counts and statistics only)

All numbers come from `GET /load/data` on a second instance (port 8091) against the production database. No identifiers, no raw values of single days. Windows are the defaults.

### 12.1 Internal load and the Garmin cross-check

| Item | Result |
|---|---|
| HR reference | maximum 196 bpm and resting 52 bpm from the Garmin `RUNNING` zone row, applied to all history |
| Activities | 590: 534 runs and 56 other activities; load from the stream 508, from the average HR 6, without HR 76 (47 runs before 2024-10, 7 runs after, 22 other activities) |
| First day with load | 2024-10-12 |
| Spearman rank correlation of app TRIMP with Garmin `activityTrainingLoad` | **0.91 over 413 matched runs** (agreement check only; Garmin's formula is UNVERIFIED) |

### 12.2 Fitness-fatigue model (#37), walk-forward

| Item | Result |
|---|---|
| Performance points | 218 steady runs (zone group 1-2) |
| Run-in | 126 days after the first load day; no point before it is used |
| Points predicted (after 180 usable days and 30 earlier points) | 126, from 2025-09-01 to 2026-10-26 |
| Mean absolute error, model | 4.46 percentage points of efficiency |
| Mean absolute error, "median of the last 28 days" | 3.42 |
| Mean absolute error, "previous point" | 3.28 (the better baseline) |
| Share of points where the model was closer than the better baseline | 33 % |
| Weights `k1` and `k2` both positive | 9.5 % of the 126 fits (k1 between 0.17 and 0.41, k2 between -0.03 and +0.005) |
| Verdict | **Not shown**: the error is 36 % higher than the better baseline instead of 10 % lower, and the weights are unstable. The page shows the load lines and the sentence of design 3.4 |

Reading: with this little data and this noisy performance measure the impulse-response model does not forecast the efficiency of the next run better than "the same as last time". That is the expected, honest outcome of rule 10; no number such as TSB is shown.

### 12.3 Recovery forecast (#15), walk-forward over the last 120 days

| Metric | Points | Error model | Error "same as today" | Verdict |
|---|---|---|---|---|
| HRV (7-day median) | 363 | 1.45 ms | 1.30 ms | not shown |
| Resting heart rate (7-day median) | 363 | 0.54 bpm | 0.44 bpm | not shown |

### 12.4 Other results worth the owner's attention (for the review, not conclusions)

- **Isolation counts (review note a), sessions / isolated with a 2-day and 3-day window:** easy 306 / 89, long run 62 / 25, tempo 50 / 7, interval 83 / 18, race 9 / 6. With a minimum of 8, tempo and race abstain in #5; easy, long run and interval pass for the first ochtend, but only the part since 2024-12-19 has a recovery baseline, so the number with a measured morning is smaller (easy 26, long run 13, interval 14 for HRV).
- **Recovery time (#10):** easy runs give "longer than 5 mornings or not measurable"; the other classes abstain (best: 7 isolated sessions with both values). The rest-day reference (#5) lies about -0.3 SD for HRV on every morning, so the owner's HRV is structurally a little under the 28-day median on days after non-running days too; the strict rule (interval includes 0 **and** |median| below 0.5 SD) therefore fails for easy runs. The rule is as designed. If the owner wants #10 to compare with the rest-day reference instead of with zero, that changes results and needs his approval.
- **#5 findings that pass the rule:** HRV the first morning after long runs 4.5 ms lower (n = 13, interval -3.4 to -0.7 SD). Everything else "geen duidelijk verschil" or "onvoldoende data".
- **#13 rest days:** HRV after a rest day +1 ms (n = 248, clear), after hard training -1 ms (n = 146, clear), Body Battery after hard training -5 points (n = 37, clear); resting HR no clear difference in any group. Rest days with another activity: 26 of 273.
- **#14 deload weeks:** 8 detected weeks (27 to 67 % of the three weeks before). Median change of the recovery values in the last 3 days is small (HRV -0.7 SD, resting HR +0.1 SD); efficiency +1.2 points over 7 weeks with enough runs.
- **#45 long run duration:** only two groups have enough isolated runs: 75 to 90 min (5, HRV -3.5 ms, no clear difference) and over 120 min (14 of 26 runs; HRV the first morning -5.0 ms with n = 7, clear). The 90 to 105 and 105 to 120 groups abstain (4 and 2 isolated runs). No "best length" is computed.
- **#31 fatigue and efficiency:** 213 runs; efficiency after a low load in the 3 days before is +0.9 % (clear), after a high load +0.0 % (no clear difference). Runs on mornings with all recovery values in the norm +2.3 % (clear), with 2 or more outside the norm +0.5 % (no clear difference).
- **#43 workout effectiveness:** 21 usable blocks of 4 weeks; interval, tempo and long run share against the efficiency change of the next block: no clear relation (p = 0.81, 0.94, 0.82).
- **#44 frequency:** four groups (1-2, 3, 4, 5 or more runs per week; 12, 19, 12, 69 weeks); weekly distance rises with frequency, so the page warns that frequency and volume cannot be separated.
- **#47 detraining:** 6 episodes of at least 14 days (all history), only 1 with at least 3 steady runs on both sides (efficiency -3.5 points); no summary.
- **#48 adaptation lag:** 86 weeks; the strongest cross-correlation is at 2 weeks (0.42) and exceeds the 95th percentile of 2000 block-shuffled series (0.35), so the page reports it as a finding with the "verband, geen oorzaak" note.
- **Fatigue signals (#11), base rate over 365 days:** 1 or more signals on 180 days, 2 or more on 58, 3 or more on 16, 4 or more on 1.
- **Recovery colouring:** on the "Belasting en herstel" chart the number of recovery values outside the own norm is 2 or more on many days and a strong deviation (at least 2 SD) is frequent. The band rules are inherited unchanged from Phase 2 (1 and 2 robust SD with a floor of 1.0 on the SD); the Phase 2 open point "the day view may flag too often" is visible here.
