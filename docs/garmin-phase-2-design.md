# Garmin Phase 2 design: recovery baselines and assessment

Date: 2026-10-25. Status: BUILT, AWAITING PHASE APPROVAL. Owner answers P2-1 to P2-8 recorded (all defaults). Code and offline tests done (section 7, section 10). Algorithm version for everything below: `recovery-v1`.

Scope (roadmap Phase 2): backlog #1 HRV baseline, #2 HRV trend, #3 resting HR analysis, #4 recovery readiness, #6 sleep quality, #7 sleep debt, #8 Body Battery trends, #9 stress analysis. Nothing else.

Basis: `GARMIN_API.md` sections 4.3 and 8, `docs/garmin-field-inventory.md`, `docs/garmin-sync-design.md` (owner decisions 2026-10-25), `docs/garmin-capability-matrix.md`.

Fact labels in this document: VERIFIED (live response or Python source), DOCUMENTED (Swagger text only), UNVERIFIED, DECISION (owner), PROPOSAL (agent choice, owner may change).

## 1. Inputs and what is stored today

Already synced and stored (Phase 1, production holds about 620 to 676 days per table, data starts 2024-12-20):

| Table (domain record) | Fields used here | Notes |
|---|---|---|
| `garmin_daily_hrv` (`GarminHrvDay`) | `lastNightAvg`, `weeklyAvg`, `lastNight5MinHigh`, baseline band (`baselineLowUpper`, `baselineBalancedLow`, `baselineBalancedUpper`), `status` | Unit ms UNVERIFIED. Measure (RMSSD or other) UNVERIFIED. Owner DECISION: label "HRV (Garmin, nachtgemiddelde)", never "RMSSD". Date = wake-up date. |
| `garmin_daily_resting_hr` (`GarminRestingHrDay`) | `restingHr` | bpm. Measurement method (sleep based or not) UNVERIFIED. |
| `garmin_daily_sleep` (`GarminSleepDay`) | `sleepTimeSeconds`, deep, light, REM, awake, unmeasurable, `sleepStartGmtMs`, `sleepEndGmtMs`, `overallScore`, `overallQualifier`, Garmin sleep need | Seconds, epoch ms UTC, VERIFIED. Sleep need unit UNVERIFIED. Date = wake-up date. `sleepTimeSeconds` excludes naps (DOCUMENTED). |
| `garmin_daily_body_battery` (`GarminBodyBatteryDay`) | `minValue`, `maxValue`, `charged`, `drained`, `latestValue` | Index 0 to 100, Garmin estimate. Day = local day. |
| `garmin_body_battery_sample` | `timestampGmtMs`, `level` | About one point per 180 s, VERIFIED. |
| `garmin_daily_stress` (`GarminStressDay`) | `avgLevel`, `maxLevel`, rest, low, medium, high, activity, uncategorized, total durations | Durations stored as ms in the record, source unit per field inventory. Level index. Garmin estimate. |
| `strava_tokens.resting_hr` | manual value | Fallback only (spec section 3, resting HR precedence). |

Not stored and therefore not used: HRV 5-minute readings, `awakeCount`, local sleep timestamps (`sleepStartTimestampLocal`), Garmin sleep sub-scores. If the owner wants sleep regularity in local clock time exact for travel days, a schema addition is needed (see open question P2-7). Default: convert with Europe/Brussels.

Read access: the repository SPI has only upserts and activity reads. Phase 2 adds **read methods** (additive, no schema change): `findHrv(from, to)`, `findSleep`, `findRestingHr`, `findBodyBattery`, `findBodyBatterySamples(fromMs, toMs)`, `findStress`. `FakeGarminRepository` gets the same methods. No new table. All results are computed on demand from stored rows (same approach as matches, decision D7): nothing derived is persisted, so an algorithm change needs no migration. The algorithm version is returned with every result.

## 2. Common rules

### 2.1 Baseline machinery (`com.running.garmin.recovery.Baseline`, core, pure, Spring-free)

For a metric series of (date, value) with nulls dropped and a target date `D`:

- **Windows:** 7, 28, 60, 90 calendar days. A window is the `W` days **before** `D` (D excluded, so today's value never defines its own baseline). DECISION from backlog #1: windows 7/28/60/90.
- **Minimum data per window:** at least 70 % of the days have a value: 7 d needs 5, 28 d needs 20, 60 d needs 42, 90 d needs 63. Below that the window is `onvoldoende data` with the count `n of W`. PROPOSAL.
- **Centre:** median. **Spread:** robust SD = 1.4826 x median absolute deviation. Floor: `max(robustSD, 1.0)` because the values are integers (HRV ms, bpm, index points). A flat series therefore cannot produce huge deviations. PROPOSAL. Median and MAD are chosen because a few outlier nights (illness, alcohol, travel, a failed measurement) should not move the personal reference.
- **Deviation:** `(value - median) / robustSD`, shown with the value, the median, the unit and n. Direction is kept (sign).
- **Deviation bands (app rule, labelled `[app-schatting]`):** `|dev| < 1` "binnen eigen norm", `1 <= |dev| < 2` "afwijkend", `|dev| >= 2` "sterk afwijkend". Higher or lower are both reported (spec section 8 rule 7): the text never says that a higher HRV is "better" and never says a low resting HR is "worse". PROPOSAL, no external validation (UNVERIFIED; same status as the Phase A heuristics).
- **Primary reference window:** 28 d for day-level deviation. 60 d and 90 d are shown for context. 7 d is the "recent level" (median of the last 7 days including D) compared with the 60 d baseline for trends. PROPOSAL.
- **Fresh data:** the evaluated day D may be incomplete (stress, Body Battery and steps of today are partial until the day ends; HRV and sleep belong to the wake-up date and are normally final in the morning). Today's stress and Body Battery daily values are flagged `onvolledig` and are never part of any baseline window.
- Result type: `BaselineResult(window, n, windowDays, median, robustSd, value, deviation, band, status)` where `status` is `OK` or `INSUFFICIENT(n, needed)`.

### 2.2 Labels (spec section 4.3)

| Label | Used for |
|---|---|
| `[gemeten]` | Values as delivered by Garmin and stored unchanged (sleep seconds, resting HR, HRV nightly average, Body Battery level). Sleep stages, Body Battery, stress and HRV values are Garmin estimates: the page adds "Garmin-schatting" in a legend, never "klinisch". |
| `[berekend]` | Medians, sums, shares, differences of measured values (baseline, deviation values, recharge, sleep shortfall). |
| `[app-schatting]` | Bands, thresholds, trend wording, the readiness summary. |
| `[onbeschikbaar]` | Missing data, with the reason (no row, null field, not enough days). |

### 2.3 Safety wording (spec section 8 rules 8 and 9, owner injury history)

Every page and prompt block with a readiness or trend statement carries: "Geen medische beoordeling. Pijn of klachten wegen zwaarder dan elke score. Normale waarden zijn geen bewijs dat een blessure of ziekte hersteld is." The app never states that someone will get injured or is safe to train. Owner history (shin stress fracture, knee dislocation) is not stored in the app (DECISION 2026-10-25).

### 2.4 Time

- `D` defaults to today in Europe/Brussels (DEFAULT decision). `?date=yyyy-MM-dd` selects another day (read only).
- Calendar dates come straight from Garmin (`calendarDate`). Clock times of sleep start and end are converted from UTC ms with Europe/Brussels (see open question P2-7).

## 3. Metrics

### #1 Personal HRV baseline

- **Input:** `lastNightAvg` per wake-up date. Label: "HRV (Garmin, nachtgemiddelde)", unit "ms" shown as UNVERIFIED in the legend.
- **Output:** per window (7/28/60/90): median, robust SD, n. Last night with deviation against the 28 d baseline. Garmin's own balanced band (`baselineBalancedLow` to `baselineBalancedUpper`) and `status` shown separately, labelled `[gemeten]` Garmin, never merged with the app baseline (never average incompatible measurements).
- **Abstain:** window below minimum (section 2.1) gives `onvoldoende data (n van W)`. No `lastNightAvg` for D gives `[onbeschikbaar] geen HRV voor deze nacht` (HTTP 204 days are not stored).
- **Compare like with like:** only `lastNightAvg` is used. `weeklyAvg` is Garmin's own 7-day figure and is shown, not mixed into the series. The protocol (nightly, sleep window) is constant, so no protocol mixing. If a baseline window contains a Garmin firmware or device change it cannot be detected (UNVERIFIED), noted in the legend.

### #2 HRV trend analysis

- **Input:** the same series.
- **Formula:** recent level = median of the 7 days ending at D (min 5 values). Compared with the 60 d baseline (excluding those 7 days: days D-66 to D-7, min 42 values). `trendDev = (recent - median60) / robustSD60`.
- **Sustained deviation:** at least 5 of the last 7 daily deviations (against 28 d, section 2.1) have the same sign and `|dev| >= 1`. Output: `aanhoudend hoger`, `aanhoudend lager`, `geen aanhoudende afwijking`.
- **Wording:** "hoger dan jouw eigen norm" or "lager dan jouw eigen norm". No judgement, no cause. If a hard session happened recently the page does not link the two (Phase 4: "association, not cause").
- **Abstain:** fewer than 5 of the last 7 days, or a baseline below minimum.
- Sparkline of the last 60 days with the 28 d median line (Chart.js, already used).

### #3 Resting heart rate analysis

- **Source order (spec):** Garmin daily `restingHr` when recorded, else the manual `strava_tokens.resting_hr` (single value, no history, so no baseline for it), else `[onbeschikbaar]`. The page always shows the source. Garmin and manual values are never mixed in one series.
- **Formula:** same baseline machinery on Garmin `restingHr` (bpm). Deviation in both directions. Method UNVERIFIED (not known whether it is sleep based): the legend says so.
- **Abstain:** as section 2.1. With only the manual value: shows the value and `geen basislijn (alleen handmatige waarde)`.
- Trend: same sustained rule as #2.

### #6 Sleep quality analysis

Per night (wake-up date D) and over the last 14 nights:

| Part | Formula | Minimum |
|---|---|---|
| Duration | `sleepTimeSeconds` (excl. naps) as h:mm `[gemeten]`; 28 d median `[berekend]` | night needs a non-null `sleepTimeSeconds`; 14 d view needs 7 nights |
| Regularity | standard deviation (minutes) of sleep start clock time and of sleep end clock time over the last 14 nights; start time measured as minutes after 18:00 so midnight does not wrap | 7 nights with start and end timestamps |
| Interruptions | awake share = `awakeSleepSeconds` / (`sleepTimeSeconds` + `awakeSleepSeconds`). `awakeCount` is not stored (see section 1), so wake-up counts are `[onbeschikbaar]` | night with both values |
| Stage shares | deep, light, REM, each as % of `sleepTimeSeconds`. Label "Garmin-schatting van slaapfasen". Nights with `unmeasurableSleepSeconds` above 10 % of the night show stages as `[onbeschikbaar]` (PROPOSAL, UNVERIFIED threshold) | night |
| Garmin score | `overallScore` and `overallQualifier` `[gemeten]` Garmin, shown next to, never replaced by, an app score | night |

- Duration, awake share and stage shares each get the baseline deviation (section 2.1). Regularity has no baseline, only the number and the n.
- No combined sleep score from the app (rule 6).
- Deep sleep reference (DECISION 2026-10-25): 15 to 20 % of total sleep as indicative, labelled `[app-schatting]`, analysed separately, **creates no sleep debt**. Later (not in Phase 2): comparison with the personal 30 d Garmin baseline once enough data exists. The baseline in section 2.1 (28 d median) already gives that personal comparison; this is shown beside the 15 to 20 % reference.
- Abstain wording: `onvoldoende data (n van 7 nachten)`.

### #7 Sleep debt

DECISION (owner 2026-10-25): target 7 h 30 total sleep per night (27 000 s), sleep debt from **total sleep only**, deep sleep never adds to the debt.

- **Config:** `garmin.recovery.sleep-target-minutes` = 450 (new key, default from the decision).
- **Formula:** over the last 7 wake-up dates ending at D: `shortfall = sum over nights of max(0, target - sleepTimeSeconds)`. Nights with more sleep than the target do **not** offset other nights (cautious: a long night does not fully repay a short week; PROPOSAL, owner may prefer a net balance, question P2-3). Also shown: number of nights below target, and the net balance `sum(sleepTimeSeconds - target)` as a second number.
- **Minimum data:** at least 5 of the 7 nights have `sleepTimeSeconds`. Missing nights are not counted as zero and not as the target; the output says `x van 7 nachten`. Below 5: `onvoldoende data`.
- **Naps:** `napTimeSeconds` is not added to total sleep in v1 (sleep need is defined on nightly sleep; PROPOSAL).
- **Garmin sleep need** (`sleepNeed.baseline`, 480 in the sample, unit UNVERIFIED): shown only as `[gemeten]` Garmin reference value without unit conversion, never used in the calculation.
- Labelled `[berekend]` (shortfall) with the target as the owner's setting. Wording: "Geschat slaaptekort", not a medical claim.

### #8 Body Battery trends

Garmin estimate (labelled). Inputs: daily `minValue`, `maxValue`, `charged`, `drained`, and the 180 s samples.

- **Overnight recharge (`berekend`):** from the sleep day of D: `wakeLevel` = first sample at or after `sleepEndGmtMs` within 15 minutes; `sleepStartLevel` = last sample at or before `sleepStartGmtMs` within 15 minutes. `recharge = wakeLevel - sleepStartLevel`. Either sample missing gives `[onbeschikbaar]`. The 15 minute tolerance is five cadence steps, PROPOSAL.
- **Daily values:** `maxValue` of D (morning peak proxy), `minValue`, `charged`, `drained` `[gemeten]` Garmin. For today these are `onvolledig`.
- **Trend:** baseline machinery on `wakeLevel` and on `recharge` (28 d primary). `charged` and `drained` get the same machinery. Sustained rule as #2.
- **Abstain:** baseline minimums; fewer than 20 of 28 days gives `onvoldoende data`.
- No conclusions about what drains the battery (association only; Phase 4 links to training).
- Day boundary local midnight (VERIFIED, `docs/garmin-capability-matrix.md`); samples are UTC ms and are compared with the sleep timestamps in UTC, so no zone logic is needed here.

### #9 Autonomic stress analysis

Garmin stress is an estimate based on heart rate variability (DOCUMENTED claim is not proven in this repo: UNVERIFIED). Labelled "Garmin-schatting".

- **Inputs:** `avgLevel`, durations.
- **Derived:** `highShare = (mediumDuration + highDuration) / (restDuration + lowDuration + mediumDuration + highDuration)`. Activity and uncategorized time are excluded (not a stress class). Unit independent (ratio of same-unit values).
- **Minimum for a day:** measured stress time (rest + low + medium + high) of at least 8 h, else the day is `te weinig meetduur` and gives no `highShare` (PROPOSAL, UNVERIFIED threshold; typical when the watch was not worn).
- **Output:** `avgLevel` and `highShare` for D with baseline deviation (28 d), 7 d recent level, trend sentence as #2. Today is `onvolledig` and excluded from baselines.
- **No time series:** stress time series is not available on any in-scope endpoint (VERIFIED), so no intraday analysis.
- Link to training: out of Phase 2 scope (Phase 4). The page shows no causal wording.

### #4 Recovery readiness (components, no score)

Backlog #4 asks for a score from HRV, resting HR, sleep, Body Battery and recent load. Rules: components shown, no unexplained 0 to 100 score (rule 6). Decisions in this design:

- **Components (four):** (a) HRV last night vs 28 d, (b) resting HR vs 28 d, (c) sleep: last night duration vs target and vs 28 d, plus the Garmin score as reference, (d) Body Battery wake level vs 28 d. Each component shows value, own baseline, deviation band, n, and its label. A component with an abstain status shows `onvoldoende data` and is not counted.
- **Summary (`[app-schatting]`):** no number. A line such as "3 van 4 componenten binnen eigen norm, 1 afwijkend (rusthartslag hoger dan eigen norm)". The summary appears only when at least 3 of 4 components have data; with 2 or fewer: `onvoldoende data voor een samenvatting` and the components still show alone. No weighting between components (no validated weights exist; UNVERIFIED).
- **Recent load is not a component in Phase 2.** The repo has no load model (`GARMIN_API.md` section 7: ATL/CTL/TRIMP do not exist; Phase 4). Garmin's `activityTrainingLoad` is UNVERIFIED. Adding load is deferred to Phase 4 item #81 and #5. This is a scope note on backlog #4's description, not a change of phase scope; see open question P2-2.
- Wording rules: deviations in both directions; no "klaar om te trainen" or "veilig"; always the safety line of section 2.3.

## 4. Code layout (after design approval)

| Module | Item |
|---|---|
| core `com.running.garmin.recovery` | `Baseline`, `RecoveryAlgorithm.VERSION = "recovery-v1"`, `HrvAnalysis`, `RestingHrAnalysis`, `SleepAnalysis` (quality + debt), `BodyBatteryAnalysis`, `StressAnalysis`, `ReadinessSummary`. Pure functions on lists of the existing records. No Spring, no Jackson beyond annotations. |
| core `spi/GarminRepository` | read methods listed in section 1 |
| runner `analysis/RecoveryService` (or `garmin/recovery`) | loads rows through the repository, calls core, reads manual resting HR from the Strava token repository |
| runner `frontend/RecoveryController` | `GET /recovery` (Thymeleaf, read only), `GET /recovery/data` (JSON, read only, no key or token). No state change, so no POST needed; `NoStateChangingGetTest` still applies |
| templates | `recovery.html` using `layout.html` (SOLID = measured or computed, DASHED = app estimate, per the existing design tokens), nav entry "Herstel". Text in Dutch |
| config | `garmin.recovery.sleep-target-minutes` (450). `GarminProperties` gets a nested `recovery` block |
| `AiAnalysisService` | see section 5 |

Logging: counts and the algorithm version only. No health value in any log line (spec 4.1).

## 5. AI prompt (entry condition from Phase A)

The guardrail at `AiAnalysisService.guardrailsBlock` states that the data contains no sleep, HRV or resting HR. That stays true only without a Garmin block.

- **Change:** make the guardrail line conditional. When a Garmin recovery block is included: the line says that the data contains Garmin recovery values (HRV nightly average, resting HR, sleep, Body Battery, stress) labelled `[gemeten]`, `[berekend]`, `[app-schatting]`, that stages, Body Battery and stress are Garmin estimates, and that conclusions about injury, illness or readiness to train are not determinable. Without the block the current text stays unchanged (existing test string unchanged).
- **Block:** `<herstel>` with the component summaries of section 3 (values, baselines, deviations, n, labels), last 7 days, no raw payloads, no identifiers.
- **Health data and external AI:** the prompt is copy-paste text that the owner pastes himself; the app calls no AI provider (spec 2.4, hard rule). Because the owner chooses where to paste health data, the block is **not** included by default (question P2-1).
- No existing Strava prompt test changes. A new test covers both guardrail variants.

## 6. Tests (offline, hand-computed)

All in `core/src/test` except controller and prompt tests in `runner/src/test`. Fixtures inline in test code (value shapes only).

| Area | Cases |
|---|---|
| `Baseline` | Median and robust SD by hand for 7 values (for example 50, 52, 48, 51, 49, 53, 47: median 50, MAD 2, robust SD 2.9652, value 56 gives dev 2.02); floor 1.0 on a flat series; D excluded from the window; 70 % minimum boundary (4 of 7 abstain, 5 of 7 ok; 19 of 28 abstain, 20 ok); nulls dropped; bands at 0.99, 1.0, 1.99, 2.0 |
| HRV (#1, #2) | normal day; abstain (3 days of history); high deviation and low deviation (both directions, wording differs, no "better"); sustained rule 4 of 7 (no) vs 5 of 7 (yes); mixed signs (no); missing night; Garmin band shown separately |
| Resting HR (#3) | Garmin preferred over manual; manual only gives no baseline; neither gives `onbeschikbaar`; higher and lower deviation |
| Sleep quality (#6) | duration, awake share, stage shares by hand; regularity across midnight (start 23:30 and 00:30 are 60 minutes apart, not 23 h); unmeasurable above 10 % hides stages; fewer than 7 nights abstains; naps excluded |
| Sleep debt (#7) | 7 nights by hand (target 450 min: nights 480, 420, 450, 400, 500, 430, 460 give shortfalls 0, 30, 0, 50, 0, 20, 0 = 100 min; net balance = 3140 - 3150 = -10 min); long night does not offset; 4 of 7 nights abstains; deep sleep low gives no debt |
| Body Battery (#8) | recharge from synthetic 180 s samples; sample 16 minutes away gives `onbeschikbaar`; today flagged incomplete and excluded; baseline abstain |
| Stress (#9) | high share by hand; day under 8 h measured gives no share; zero durations (no division by zero) |
| Readiness (#4) | 4 of 4 data; 3 of 4 (summary shown); 2 of 4 (summary abstains, components shown); every component abstain; safety line always present; no numeric score in the output type |
| Service and controller | `RecoveryService` with `FakeGarminRepository`; `GET /recovery` renders with and without data and shows source labels; `/recovery/data` has no key or token field; `NoStateChangingGetTest` passes |
| Prompt | guardrail text with and without the Garmin block; Strava prompts unchanged |
| Logs | a test captures logging during a recovery computation and asserts no value from the fixture appears |

Strava tests: none expected to change. If one must, old and new values go in this doc and in the progress log.

Temporal validation (spec rule 10): not applicable, Phase 2 builds no predictive model. Baselines use only days before D by construction (tested: a future spike changes nothing).

## 7. Exit criteria check (filled in at the end of the build)

| Criterion | Status |
|---|---|
| Design doc approved | Approvals table shows APPROVED (set by the owner, confirmed in session 2026-10-25) |
| Each metric has normal, abstain and deviation tests | DONE: `BaselineTest` (11), `RecoveryAnalysisTest` (33), `RecoveryTest`, `RecoveryControllerTest`, `AiPromptRecoveryGuardrailTest` |
| No health data in logs | DONE: `RecoveryService` logs counts and the version only; a test captures its log and checks no fixture value appears |
| Owner approval of the phase | AWAITING OWNER |

## 8. Questions for the owner: ALL ANSWERED 2026-10-25, every default accepted (record in `GARMIN_PROGRESS.md`)

| # | Question | Default if not answered |
|---|---|---|
| P2-1 | Should the copy-paste prompts include the Garmin `<herstel>` block at all, and if so on by default or only on request? | Not included by default. Guardrail made conditional; block added behind an explicit option |
| P2-2 | Readiness without recent load (load model arrives in Phase 4). Accept? | Accept: four components, no load |
| P2-3 | Sleep debt: shortfall only (long nights do not offset) or net balance as the main number? | Shortfall main, net balance shown second |
| P2-4 | Deviation thresholds 1 and 2 robust SD and the 70 % minimum data rule: keep as proposed? | Keep, labelled `[app-schatting]` |
| P2-5 | Primary baseline window 28 d (60 d for trend), 8 h minimum stress measuring time, 10 % unmeasurable sleep rule: keep? | Keep |
| P2-6 | Sleep need (`sleepNeed`, Garmin) shown as a reference without unit, or hidden until its unit is proven? | Shown without unit, UNVERIFIED |
| P2-7 | Sleep regularity uses Europe/Brussels to convert UTC times. Add the stored local sleep timestamps (nullable additive columns, one re-sync of sleep) to be exact when travelling? | Europe/Brussels, no schema change |
| P2-8 | Nav entry "Herstel" and page path `/recovery`: ok? | Yes |

## 9. Not in Phase 2

Training load, training response (#5), recovery time (#10), fatigue detection (#11), illness (#12), rest or deload effectiveness, any link between sessions and recovery values, race readiness, injury module, FIT decoding, writing to Garmin, any external AI call.

## 10. Build notes (2026-10-25, details the design left open)

- **Unmeasurable share:** `unmeasurableSleepSeconds / (sleepTimeSeconds + awakeSleepSeconds + unmeasurableSleepSeconds)`. Above 10 % (strictly) hides the stage shares. A null `unmeasurableSleepSeconds` does not hide them (UNVERIFIED).
- **Regularity SD:** population standard deviation of minutes after 18:00 local (Europe/Brussels), 7 nights needed in the last 14.
- **Units of the baseline series:** sleep duration in minutes, shares in percent, so the SD floor of 1.0 means 1 minute or 1 percentage point.
- **Sustained rule:** counted over the days of the last 7 that have a value and an OK 28 day baseline. Needs at least 5 such days, and at least 5 with the same sign and |deviation| >= 1.
- **Stress:** `avgLevel` is analysed on every day with data; only `highShare` needs 8 h measured time.
- **Body Battery:** wake level and recharge baselines include today (they belong to the wake-up date); `charged` and `drained` of today are excluded.
- **Readiness component values:** HRV last night, resting HR, sleep duration in minutes, Body Battery wake level, each against its 28 day median.
- **P2-1 implementation:** `GET /ai?herstel=true` and `/ai/download?herstel=true` add the `<herstel>` block and switch the guardrail. Default prompts and the other three prompts are unchanged. Owner Strava prompt tests unchanged.
- **P2-8:** `/recovery` (Thymeleaf), `/recovery/data` (JSON, read only), nav entry "Herstel". Config: `garmin.recovery.sleep-target-minutes` (450).
- **Repository SPI:** six read methods added (`findHrv`, `findSleep`, `findRestingHr`, `findBodyBattery`, `findBodyBatterySamples`, `findStress`). No schema change, no new table.
- **Not verified:** the page was rendered in a Spring test context (H2), not opened in a browser against production data. Chart.js loads from a CDN as on the other pages.
- **Strava tests:** none changed. `NoStateChangingGetTest` now also covers `RecoveryController`.
- **Tests:** 209 pass, 0 fail (`:core:test :runner:test`, Java 23): 152 before, 57 new.
- **Owner additions in session (2026-10-25, after first look at the page):** (1) `POST /recovery/garmin-update` button, same coordinator and single-run lock as `POST /garmin/sync`; Phase 1 had "status UI JSON only", this adds a button, not a state-changing GET. (2) Week (Monday to Sunday) and month (calendar month) views: median, range and n per metric over the days with data (minimum 70 % of the elapsed days), set beside the same number of days of the previous period; difference only, no judgement; period sleep shortfall by the day-view rule. (3) Default day = latest day with data when today has none. (4) The readiness text distinguishes "no value for this day" from "not enough history". Tests: 220 pass in total.
- **Hero card (owner request, option "spectrum + conclusion card, no number"):** levels and rules as in the progress log; spectrum position = (clamp(deviation, -3, 3) + 3) / 6 x 100; no numeric score anywhere (test checks the output type has no score field). Thresholds are UNVERIFIED app estimates.
- **Sleep target in the label (owner request after review):** `SleepTargetCheck`: average shortfall per night against `garmin.recovery.sleep-target-minutes`, over the nights with data (7 nights day view, the period in week and month view, minimum 5 resp. 70 %). z = -(average shortfall) / 20 min, banded like the other deviations (under 20 min within, 20 to 40 deviating, 40 and more strong). PROPOSAL, UNVERIFIED. Readiness counts sleep once; the larger deviation of own norm and target decides, so structural short sleep can no longer read "binnen je norm".
- **Week and month conclusion:** `PeriodReport.readiness`: period median per component against the median and robust SD of the 28 days before the period (minimum 20 days), same bands and level rules as the day view; no sustained-trend sentence in period view. Charts per day with period median (and the sleep target line).
