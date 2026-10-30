# Garmin Phase 3 design: performance analytics

Date: 2026-10-30. Status: APPROVED by the owner in session 2026-10-30 (written instruction; questions P3-1 to P3-11 answered in section 9). Built on 2026-10-30 (tenth session), AWAITING APPROVAL of the phase; see sections 8, 11 and 12. Algorithm version for everything below: `performance-v1`.

Scope (roadmap Phase 3, confirmed by the owner in session 2026-10-30; #21 added by the owner later the same day): backlog #16 aerobic efficiency index, #17 aerobic efficiency trends, #18 heart rate drift, #19 aerobic decoupling, #21 fitness trend model, #22 threshold estimation, #24 heart rate zone distribution, #25 durability, #26 long run fatigue, #27 interval performance, #28 heart rate recovery, #29 power efficiency, #30 terrain-adjusted performance, #32 comparable run finder, #40 intensity distribution, #46 training consistency. Nothing else.

Scope note: #21 (fitness trend model) was not in the roadmap list. The owner added it to Phase 3 in session 2026-10-30 (recorded in `GARMIN_PROGRESS.md`; the roadmap itself is unchanged, this note is the reason). #20 (running economy proxy) stays out (P3-9). The training load model is Phase 4.

Basis: `GARMIN_API.md` sections 4.3 and 8, `GARMIN_ROADMAP.md` Phase 3, `docs/garmin-audit-remediation.md` (Phase A, approved), `docs/garmin-phase-2-design.md` (labels, baseline style), `docs/garmin-field-inventory.md`, `docs/garmin-capability-matrix.md`.

Fact labels in this document: VERIFIED (code, live response, or Python source), DOCUMENTED (Swagger text only), UNVERIFIED, DECISION (owner), PROPOSAL (agent choice, owner may change).

## 1. Inputs and what is stored today

Phase 3 is mostly a Strava stream analysis. Strava stays the source of truth for activities and streams (`GARMIN_API.md` section 3, source precedence). Garmin adds reference data only.

| Source | What | Used for | Status |
|---|---|---|---|
| Strava `activities` | distance, moving and elapsed time, elevation gain, `workout_type`, average and max HR, `is_trainer`, `is_manual`, `average_watts`, `weighted_average_watts`, `device_watts`, `start_latlng`, local time zone | filters, terrain class, power (activity level), consistency | VERIFIED (`Activity.kt`) |
| Strava `activity_streams` | `time`, `distance`, `latlng`, `altitude`, `heartrate`, `cadence`, `velocity_smooth`, `grade_smooth`, `temp`, stored as JSON text | drift, decoupling, durability, zones, HR recovery, GAP, thresholds | VERIFIED (`ActivityStream.kt`, `StravaApiClientImpl` keys). Sample interval and per-activity coverage UNVERIFIED (see 1.1) |
| Strava `activity_laps` | per-lap time, distance, speed, HR, cadence | interval reps (#27), session type | VERIFIED |
| Strava best efforts (`best_efforts_json`) | Strava-computed best efforts | cross-check for #22 | VERIFIED (Phase A) |
| Existing core code | `SessionClassifier`, `LapClassifier`, `WorkoutStructure`, `EfficiencyFactor`, `EfVerdict`, `HrZones`, `BestEffortFinder`, `RunAggregates`, `ActivityTime`, `DateRange` | reused, not rewritten | VERIFIED, Phase A approved |
| Garmin `garmin_hr_zones` (`GarminHeartRateZones`, one row per sport) | `zone1Floor` to `zone5Floor`, `maxHr`, `restingHr`, `lactateThresholdHr` for sport `RUNNING` | optional HR zone set (#24, #40), reference threshold HR (#22) | VERIFIED (2 records seen: `DEFAULT`, `RUNNING`, method `LACTATE_THRESHOLD`). The table keeps the latest snapshot per sport, so the history of zone changes is not known (UNVERIFIED) |
| Garmin `garmin_activity` (`GarminActivityRef`) | summary values, training effect, Garmin training load | not used in Phase 3 | Garmin training load is UNVERIFIED and belongs to Phase 4 |
| Garmin FIT files | 1 Hz records incl. power, vertical oscillation, stance time | NOT used | Not stored (spec section 3). Decoder and storage are a Later decision (P3-5) |

Not available, therefore not built:

- **No power stream.** Strava streams here have no `watts` key and the FIT files are not stored. Power analysis (#29) is limited to activity-level averages.
- **No running-dynamics time series** (ground contact, vertical oscillation) outside the FIT files.
- **No per-sample temperature guarantee.** `temp` is requested but may be empty (the Garmin FIT files seen in Gate 1 have no temperature field in `record`; whether Strava fills `temp` for these activities is UNVERIFIED).

### 1.1 Data census (first build step, counts only)

Before any formula is trusted the build starts with a read-only census on the production data (counts only, nothing committed, no identifiers): number of runs; runs with streams; runs with HR stream and share of samples with HR; median sample interval and its spread; share of runs with `grade_smooth`, `temp`, `cadence`, `altitude`; runs with laps; runs with `device_watts = true` and non-null `average_watts`; distribution of run duration. The numbers go into section 11 of this document and decide which metrics are `onbeschikbaar` for the owner's data. A metric whose minimum sample size cannot be met by the real data stays in the code but is reported as `onvoldoende data`.

## 2. Common rules

### 2.1 Run eligibility (shared filter, `RunFilter`)

A run enters a stream-based analysis only if all hold (PROPOSAL, owner may change):

- sport is running (the same type test the existing services use), not `is_manual`, not `is_flagged`;
- has `time`, `distance` and `heartrate` streams with at least 5 minutes of valid samples;
- `is_trainer` runs are allowed for HR and zone metrics but are excluded from terrain, GAP and pace comparisons (treadmill speed comes from a footpod or belt, not GPS);
- Strava `workout_type` and laps decide the session type through `SessionClassifier` (races, intervals, tempo and strides are never counted as "steady").

### 2.2 Stream cleaning (`CleanRun`, core, pure)

Input: the stored `ActivityStream`. Output: a list of samples `(t, distance, hr, speed, grade?, temp?, cadence?)` plus a coverage record. Rules, all PROPOSAL, each testable:

1. Use the shortest common length of `time`, `distance`, `heartrate`. Other streams are used only when their length also matches.
2. A sample is dropped when `dt <= 0`, when `dt > 30 s` (pause or recording gap, so no interpolation across it), when HR is outside 40 to 230 bpm, when the sample speed `d(distance)/dt` is above `BestEffortFinder.MAX_SAMPLE_SPEED_MS` (12.5 m/s, GPS jump), or when the sample speed is below 0.5 m/s (standing still).
3. HR dropout: 30 or more consecutive identical HR values are treated as a failed strap reading and dropped.
4. Speed per sample is the sum of distance over the sum of time in a 30 s centred window (reduces GPS noise). `velocity_smooth` is not used because its smoothing is not documented (UNVERIFIED).
5. Every average is time weighted with `dt`. A run is analysed only when valid HR time is at least 90 % of its moving time; otherwise `onbeschikbaar (HR-dekking x %)`.
6. The sample interval is never assumed to be 1 s. The code works from `time`.

### 2.3 Steady run (shared by #16 to #19, #25, `SteadyRun`)

The analysis window `W` is the moving time after the first 10 minutes (HR lags the effort at the start; PROPOSAL). A run is a **steady run** when:

- session type is `STEADY` (not `RACE`, `INTERVAL`, `TEMPO`, `STRIDES`), not a hard effort (`HrZones.isHardEffort`, average HR below 85 % of the estimated max HR);
- `W` lasts at least 30 minutes;
- cut into 5 minute blocks, the coefficient of variation (SD / mean) of block speed is at most 10 % (the last block is dropped when shorter than 2.5 minutes);
- Strava elevation gain is at most 15 m per km (terrain not hilly; hilly runs go to #30);
- HR coverage rule 2.2 point 5 holds.

When one test fails the run is excluded and the page says which test failed (counts per reason are shown, so the owner can see why a run does not count).

### 2.4 Minimum data (rule 4 of the spec, abstain with counts)

| Metric | Per run | For a trend or summary |
|---|---|---|
| #16 index | steady run, `W` of at least 30 min | 5 runs in the stratum and window |
| #17 trend | | 5 runs in each of the two windows (EF verdict rules reused, 3.2) |
| #18 drift, #19 decoupling | steady run, `W` of at least 30 min | 5 qualifying runs in the last 90 days |
| #21 fitness view | | at least 2 of its 4 indicators have a verdict other than `onvoldoende data` |
| #22 threshold | | 3 anchors, duration ratio of the longest to the shortest at least 2, all within 3 to 30 min, in the last 90 days |
| #24, #40 zones | run with HR coverage of at least 90 % | the summary names the number of runs and the minutes of data |
| #25 durability | steady run of at least 75 min | 5 such runs |
| #26 long run | long run (3.8) with at least 75 min valid | 3 long runs for a list, 5 for a trend |
| #27 intervals | at least 3 detected reps for fade statistics (2 reps: list only) | 3 sessions with the same rep signature |
| #28 HR recovery | rep end meets protocol (3.10) | 10 measurements in the same protocol |
| #29 power | `device_watts` true and `average_watts` present | 5 runs, and 70 % coverage of eligible runs in the window |
| #30 GAP | grade stream present and plausible | none (per run) |
| #32 finder | reference run with the hard criteria data | at least 3 matches, else `te weinig vergelijkbare runs (n = x)` |
| #46 consistency | | 8 complete weeks |

Every number above is a PROPOSAL and UNVERIFIED (no external validation); they are config values (`garmin.performance.*`, see section 5) so the owner can change them without code changes.

### 2.5 Labels, trends and wording

- Labels as in Phase 2: `[gemeten]` (stored values), `[berekend]` (computed from measured values), `[app-schatting]` (heuristics, bands, GAP, thresholds, session type), `[onbeschikbaar]` (with reason).
- A trend always shows its sample counts per window and the median or mean it is based on. A trend is a statement about the runs that qualified, not about all running.
- Trend verdict reuses `EfVerdict` (Phase A, verified): change of the pooled value and of the per-session mean must both exceed 3 %, point the same way, and exceed 2 standard errors; otherwise `stabiel`, `onzeker` or `onvoldoende data`. Wording: "indicatie", never "je bent fitter geworden".
- Rule 5 note on every relationship (EF against temperature or terrain, drift against heat or fatigue): "Verband, geen oorzaak. Temperatuur, terrein, slaap, vermoeidheid en hartslagband beinvloeden deze waarden."
- Safety wording where a result could be read as a health statement: "Geen medische beoordeling. Pijn of klachten wegen zwaarder dan elke score." No result says a run was good or bad for health, or that the athlete is safe to train. No 0 to 100 score exists anywhere (rule 6).
- Rule 3, compare like with like: trends only compare runs in the same intensity stratum (3.1) and flag temperature, terrain and trainer differences. Where the comparison would mix incompatible conditions the app refuses and says why.

### 2.6 HR zone set (used by #24, #40, strata in #16)

Two zone sets exist: Garmin's `RUNNING` zones (floors per zone, lactate-threshold based, owner's watch setting) and the app's `HrZones.pctMax` on the 12-month max HR estimate (Coach page). They are never mixed in one view. Default (question P3-1): Garmin `RUNNING` zones when present, labelled "Garmin-instelling (laatst gesynchroniseerd)", else the app zones labelled `[app-schatting]`; `?zones=app|garmin` switches. Because zone history is unknown, old runs are classified with today's zones; the page says so. Zone 5 has no upper bound above the maximum measured HR.

## 3. Metrics

### 3.1 #16 Aerobic efficiency index

- **Definition:** efficiency factor of the steady-run window `W`: `EF = sum(distance) / sum(HR x time)` over valid samples, in metres per heartbeat (existing `EfficiencyFactor.pooled`, shown the existing way). Computed on `W`, not on the whole run, so warm-up HR lag is excluded.
- **Stratum (comparable effort):** the HR zone (2.6) of the window's time-weighted average HR. EF is compared only inside one stratum (zone 1 to 2 together, zone 3, zone 4 and above are shown as separate lines). Runs of different intensity are never pooled.
- **Per run output:** EF, average speed, average HR of `W`, stratum, minutes of `W`, flags (temperature class when known, `heuvelachtig` is excluded by 2.3, trainer).
- **Supersedes nothing:** the existing period comparison (`PeriodComparisonService`) keeps its whole-run EF. The two answer different questions and the page labels this ("hele run" against "stabiel deel").
- **Abstain:** 2.4. Unknown temperature is shown as `onbekend`, not guessed.

### 3.2 #17 Aerobic efficiency trends

- **Windows:** last 28 days against the 28 days before, and last 90 days against the 90 days before, per stratum. A monthly chart of the median EF per stratum with the number of runs per point.
- **Verdict:** `EfVerdict.evaluate` with `minSessions = 5` (rules in 2.5). The number of runs, the two medians and the percentage change are always shown.
- **Pace at the same HR:** per stratum the median pace and median HR of the qualifying runs are shown next to EF, so a change can be read as "faster at the same HR" or "lower HR at the same pace". No regression model in v1.
- **Confounders shown, not corrected:** median temperature per window (if known), median elevation gain per km, share of trainer runs (zero here, see 2.1). When the median temperature differs by more than 8 degrees C between windows (PROPOSAL) the verdict is capped at `onzeker`.

### 3.3 #18 Heart rate drift

- **Applies to:** steady runs (2.3).
- **Formula:** `W` is split at half of its moving time into `H1` and `H2`. `driftBpm = HR(H2) - HR(H1)`, `driftPct = driftBpm / HR(H1) x 100`, time-weighted. The speed ratio `speed(H2) / speed(H1)` is shown. Drift is reported only when that ratio is within 0.95 to 1.05 (PROPOSAL); otherwise the run is `onbeschikbaar (tempo veranderde te veel)` and decoupling (3.4) is the right measure for it.
- **Summary:** median `driftPct` of the last 5 or more qualifying runs in 90 days, with the list and the temperature of each run. Positive means HR rose.
- **Wording:** a number and its direction. The app gives no limit above which drift is "bad". A commonly quoted 5 % figure is shown only as "veelgebruikte vuistregel" with a UNVERIFIED label, not as a verdict.
- **Abstain:** 2.4, one reason per excluded run.

### 3.4 #19 Aerobic decoupling

- **Formula:** same halves as 3.3. `EF1 = speed(H1) / HR(H1)`, `EF2 = speed(H2) / HR(H2)`, `decouplingPct = (EF1 - EF2) / EF1 x 100`. Positive means less speed per heartbeat in the second half.
- **Speed basis:** raw speed in v1. Grade-adjusted speed (3.12) is shown as a second column when the grade stream is plausible; the two are never averaged. No power-based decoupling (no power stream, section 1).
- **Difference from 3.3:** drift answers "did HR rise at the same speed", decoupling also tolerates a speed change between halves. Both use the same eligibility, so the same runs appear in both tables.
- **Summary and wording:** as 3.3.

### 3.4a #21 Fitness trend model

A single tracked view of the slow variables, built only from metrics in this phase. It is a **model of presentation, not a new formula and not a score** (rule 6).

- **Indicators (4):** (a) aerobic efficiency trend, steady runs, per stratum (#17); (b) estimated critical speed against the previous fit (#22); (c) median HR drift of steady runs (#18); (d) EF change at minute 60 (#25). Each keeps its own sample counts, windows (last 90 days against the 90 days before) and abstain rules from its section.
- **Direction per indicator:** `hoger`, `lager`, `stabiel`, `onzeker`, `onvoldoende data`, from the verdict rules of 2.5. Whether the direction is favourable is stated in words per indicator ("hogere EF = meer afstand per hartslag", "lagere drift = minder HR-stijging"); the app does not add them up and uses no weights (no validated weights exist, UNVERIFIED).
- **Summary (`[app-schatting]`):** no number. A line such as "van 4 indicatoren: 2 in gunstige richting, 1 stabiel, 1 onvoldoende data". Shown only when at least 2 indicators have a verdict; else `onvoldoende data voor een samenvatting` and the indicators show alone.
- **Chart:** the monthly series of each indicator side by side (own axis each, no merged index), with run counts per point.
- **Slow variables only:** fast variables (recovery, fatigue, load) are not part of it; they come in Phase 2 pages and Phase 4. The view says so.
- **Wording:** "indicatie", "verband, geen oorzaak", no statement about race ability (Phase 5), no medical claim.

### 3.5 #22 Threshold estimation

The app does not know the lactate threshold. What can be estimated from the data is a **critical speed (CS)** from maximal-ish efforts. CS is a related but different quantity, so the page says "geschatte kritische snelheid" and never "lactaatdrempel".

- **Anchors:** best efforts over exact distances from the streams (`BestEffortFinder.fastest`, Phase A) for 1 km, 3 km (new distance, PROPOSAL), 5 km and 10 km, restricted to those with duration 3 to 30 minutes, within the last 90 days. Each anchor is the fastest effort of that distance in the window. Races marked on Strava are flagged. Strava's own best efforts are shown next to them as a cross-check (as the PB logic does).
- **Model:** linear critical speed model `distance = CS x time + D'` fitted by least squares on the anchors (Jones et al. style two-parameter model; PROPOSAL).
- **Outputs:** `CS` as pace per km, `D'` in metres, `R2`, number of anchors, and the **uncertainty**: the range of CS when each anchor is left out in turn. `HR at CS`: median HR over the segments of the anchors with 10 to 30 min duration (the segment index range is known from `BestEffortFinder`), with n.
- **Abstain:** fewer than 3 anchors; duration ratio below 2; `R2` below 0.98; a CS range wider than 5 % of CS ("schatting te onzeker"). A fastest effort in the window may not have been a maximal effort, so CS is a lower bound on current ability; the page says that.
- **Garmin reference:** `lactateThresholdHr` and its sport from `GarminHeartRateZones` is shown as a separate line `[gemeten] Garmin-schatting` and is never merged into the app estimate (never average incompatible measurements). Garmin's `lactateThresholdSpeed` unit disagrees inside its own Swagger document (UNVERIFIED) and is not shown.
- **Validation (rule 10, temporal):** for each anchor date the model is fitted only on anchors before that date and used to predict the next anchor within its duration range; the prediction error is shown as "eerdere voorspellingen weken x % af". This also serves as the validation set the backlog asks for.

### 3.6 #24 Heart rate zone distribution

- **Per run:** time in each of the 5 zones (2.6), from valid samples, as minutes and as percentage of valid HR time. Runs below 90 % HR coverage are `onbeschikbaar`.
- **Per period:** per ISO week and per month (activity-local dates, the existing `WeeklyVolume` convention), the sum of minutes per zone and its share. The page shows the number of runs and the minutes of data that the period contains, and the zone set used.
- **Stored zone history:** unknown (2.6), so the page repeats the "huidige zones toegepast op oude runs" note.
- **No Garmin time-in-zone:** Garmin's own per-activity time-in-zone endpoint is not in the in-scope endpoint list and is UNVERIFIED, so it is not used.

### 3.7 #40 Intensity distribution

- **Time based:** the zone minutes of 3.6 grouped into three classes: easy (zone 1 and 2), moderate (zone 3), hard (zone 4 and 5). This is an app grouping and not Seiler's three zones (those use the first and second ventilatory thresholds, which the app does not know); the label says so. PROPOSAL.
- **Session based:** number and distance share of sessions by `SessionType` (steady, strides, tempo, interval, race) per week and per 4 weeks.
- **Output:** two stacked bars per period with counts. No judgement about polarised, pyramidal or any "ideal" distribution (no validated target for this athlete). Both views always show the period's run count.
- **Abstain:** a period with fewer than 3 runs shows the values and the text `weinig runs (n = x)`.

### 3.8 #25 Durability and #26 Long run fatigue

Both use the same engine (`DurabilityProfile`): the run is cut into consecutive 10 minute moving-time blocks after the first 10 minutes. A block counts when it has at least 80 % valid samples. Per block: speed, HR, EF, cadence in spm (existing `Cadence`), mean grade.

**#25 Durability (steady runs of at least 75 minutes):**

- Output per run: EF per block as a percentage of the EF of the first counted block, and the **EF change at minute 60** (block around minute 60 against the first block).
- Summary: median EF change at minute 60 over the qualifying runs (5 needed), with the list. A trend over time uses the verdict rules of 2.5 (change of the median between the last 90 days and the 90 days before).
- Comparison across runs only inside the same pace stratum (3.1).

**#26 Long run fatigue (long runs, any pace profile):**

- A long run: Strava `workout_type` 2 (long run) or moving time at least 75 minutes and at least 1.5 times the median run duration of the last 90 days (PROPOSAL).
- Output per long run: thirds by moving time (first third excluding warm-up against last third): change of speed, HR, EF and cadence (spm), and the share of the run that is slower than the first third's speed by more than 5 %. Grade is shown per third because a hillier last third is a confounder; if the mean absolute grade differs by more than 2 percentage points between the thirds the EF comparison is marked `onzeker (verschillend terrein)`.
- Summary: median change per measure over the long runs (3 for a list, 5 for a trend). Durations are shown. Never "je werd moe"; the number is shown as a change, with the rule 5 note.

### 3.9 #27 Interval performance

- **Reps:** only sessions typed `INTERVAL` by `SessionClassifier`; reps from `LapClassifier.repEfforts` with `realReps` (at least 30 s). Every statement carries "automatisch herkend" (the classifier is a labelled heuristic, accepted in Phase A).
- **Per session:** per rep distance, time, pace, average HR, max HR, cadence (spm); rep signature = number of reps and median rep distance rounded to 50 m (reps within `WorkoutStructure.SAME_SIZE_TOLERANCE` of each other).
- **Statistics (3 or more reps):** pace fade = `(pace of last rep - pace of first rep) / pace of first rep`; pace spread = coefficient of variation of rep speed; HR rise = average HR of the last rep minus the first rep. With 2 reps the values are listed, no statistics.
- **Recovery:** the recovery lap duration and the heart rate recovery of 3.10 when its protocol allows.
- **Across sessions:** a trend only over sessions with the same rep signature (3 needed), with their dates. Different signatures are never pooled (rule 3).

### 3.10 #28 Heart rate recovery

- **Definition:** for a rep end `E` (from 3.9): `HRend` = highest 5 second average HR in the last 15 s before `E`. `HRR60 = HRend - HR(E + 60 s)` where `HR(E + 60 s)` is the average of `E + 57 s` to `E + 63 s`. Needs valid samples in both windows and no gap longer than 5 s.
- **Protocol classes (never mixed):** `staand` (speed below 0.5 m/s for the whole 60 s), `wandelen` (below 2 m/s), `joggen` (otherwise, the recovery lap is a jog). The class is stored with every value.
- **Validity:** `HRend` must be at least 85 % of the estimated max HR (`HrZones.HARD_EFFORT_FRACTION`), otherwise the rep was too easy for HRR to mean anything.
- **Summary:** per protocol class the median `HRR60` of the last 90 days (10 measurements needed), with n and the dates.
- **Caveat on screen:** HR recovery after a run interval is a proxy; the 60 s measure comes from exercise testing, not from a run interval with variable rest. Whether the Strava stream resolution is fine enough is checked in the census (1.1). If the median sample interval is above 5 s the metric is `onbeschikbaar` for the whole data set.

### 3.11 #29 Power efficiency (activity level only)

- **Inputs:** Strava `average_watts`, `weighted_average_watts`, `device_watts`, speed and HR. No power stream exists, so no time-resolved analysis (no power-based decoupling).
- **Eligibility:** `device_watts = true` (measured, not estimated by Strava) and the run is steady (2.3). Whether a Garmin watch's running power arrives with `device_watts = true` is UNVERIFIED and part of the census. If fewer than 70 % of the eligible runs of the window have measured power the metric is `onbeschikbaar (vermogen niet betrouwbaar aanwezig)`.
- **Formulas:** `wattsPerBpm = averageWatts / averageHr`, and `speedPerWatt = averageSpeed / averageWatts` (m/s per W). Trend by the rules in 2.5 with 5 runs per window.
- **Wording:** wrist or pod based running power is a device estimate; the page says "vermogen volgens je apparaat" and no claim about running economy is made (backlog #20 is out of scope).

### 3.12 #30 Terrain-adjusted performance (grade-adjusted pace)

- **Formula:** grade-adjusted speed (GAP) uses the published energy cost of running of Minetti et al. (2002): `C(i) = 155.4 i^5 - 30.4 i^4 - 43.3 i^3 + 46.3 i^2 + 19.5 i + 3.6` (J per kg per m, `i` = grade as a fraction, valid for -0.45 to +0.45). Per sample the flat-equivalent speed is `speed x C(i) / C(0)` with `C(0) = 3.6`. Run GAP = `sum(distance x factor) / sum(time)`. Hand check: at grade +10 % `C = 5.968`, factor `5.968 / 3.6 = 1.658`.
- **Grade source:** `grade_smooth` from the stream. Strava's own smoothing is undocumented (UNVERIFIED), altitude from GPS or barometer is noisy. Samples with a grade beyond +/- 20 % are ignored. A run is `onbeschikbaar` when more than 5 % of its samples have a grade beyond +/- 25 % or the stream is missing.
- **Limits stated on the page:** the model describes steady running on a treadmill-like gradient, it is less valid on steep downhills, technical trail and when walking; it is an `[app-schatting]`. It is not Strava's GAP (that formula is not published).
- **Use:** shown next to raw pace per run, as an optional second EF in 3.1 (`EF(GAP)`), and in the comparable finder (3.13). Raw and adjusted values are never mixed in a trend. Trainer runs are excluded.
- **Terrain class (also used by 3.13):** from Strava elevation gain per km: `vlak` below 5 m/km, `glooiend` 5 to 15, `heuvelachtig` above 15 (PROPOSAL). Elevation gain from Strava is itself noisy, so a class is a coarse filter.

### 3.13 #32 Comparable run finder

Input: one reference run (`/performance/run/{id}`). Output: up to 10 other runs, the most similar first, with a table of every criterion per match. **No similarity score** (rule 6): the order is by number of soft criteria that agree, ties by the smaller date difference, and the table shows each criterion.

Hard criteria (all must hold, otherwise the run is not a candidate; this is how incompatible conditions are refused, rule 3):

- same session type (`SessionClassifier`); races only match races;
- both outdoor or both trainer;
- distance ratio between 0.8 and 1.25;
- same terrain class (3.12);
- both have HR, or both do not;
- the candidate is not manual or flagged.

Soft criteria (shown as agree, differ or unknown, never hidden):

- average HR within 5 bpm of each other, and the same HR zone of the average;
- temperature within 5 degrees C (`onbekend` when either is missing);
- start point within 1 km (route likeness, from `start_latlng`);
- elevation gain per km within 5 m/km;
- calendar distance in days.

Output per match: date, distance, pace, GAP when plausible, average HR, EF of `W` when steady, drift when steady, and the difference to the reference for each. No verdict; the page says "Verband, geen oorzaak." With fewer than 3 matches: `te weinig vergelijkbare runs (n = x)` plus the criterion that removed most candidates ("meeste runs vielen af op: afstand").

### 3.14 #46 Training consistency

- **Inputs:** all running activities (Strava), activity-local dates, `WeeklyVolume` weeks (Monday start), complete weeks only (the current week is excluded and shown as `lopend`).
- **Windows:** the last 12 and the last 52 complete weeks.
- **Components (no score):** runs per week (distribution: 0, 1, 2, 3, 4 and more), share of weeks with at least 1 run, longest gap in days between two runs, number of gaps of 7 days or longer (listed with their dates), longest run of consecutive weeks with at least one run, and the coefficient of variation of weekly distance. Median weekly distance and median runs per week are shown as the reference.
- **Wording:** neutral numbers. No target number of runs per week is assumed and the app does not call a pattern good or bad. Gaps are shown as facts; their cause (injury, illness, travel) is unknown to the app.
- **Abstain:** fewer than 8 complete weeks of history gives `onvoldoende data (n weken)`.

## 4. Source precedence and Garmin use (spec section 3)

Strava owns activity identity, distance, pace, streams and laps. Garmin supplies the optional zone set and the Garmin lactate threshold HR as labelled reference values. Garmin and Strava values are never averaged. No Garmin recovery value (HRV, sleep, Body Battery) is used as input in Phase 3 (linking recovery to performance is Phase 4; the comparable finder does not filter on it).

## 5. Code layout (after design approval)

| Module | Item |
|---|---|
| core `com.running.strava.analysis.performance` | `PerformanceAlgorithm.VERSION = "performance-v1"`, `CleanRun`, `RunFilter`, `SteadyRun`, `DriftAnalysis` (#18, #19), `EfIndex` (#16, #17, reuses `EfficiencyFactor` and `EfVerdict`), `ZoneDistribution` (#24, #40), `DurabilityProfile` (#25, #26), `IntervalPerformance` (#27), `HeartRateRecovery` (#28), `PowerEfficiency` (#29), `GradeAdjusted` (#30), `FitnessView` (#21, combines the verdicts of #17, #18, #22, #25 without new formulas), `CriticalSpeed` (#22), `ComparableRuns` (#32), `Consistency` (#46). Pure functions on `Activity`, `ActivityStream`, `Lap`, `GarminHeartRateZones`. No Spring. |
| core `garmin/spi/GarminRepository` | read method `findHeartRateZones()` for the `RUNNING` and `DEFAULT` rows (additive, no schema change). `FakeGarminRepository` gets the same method |
| runner `analysis/PerformanceService` | loads runs, streams and laps through the repositories, calls core, keeps an in-memory cache of per-run results keyed by activity id and algorithm version (P3-7) |
| runner `frontend/PerformanceController` | `GET /performance` (overview), `GET /performance/run/{id}` (one run), `GET /performance/data` (JSON, read only). All GET and side-effect free, so `NoStateChangingGetTest` still applies and is extended |
| templates | `performance.html`, `performance-run.html` using `layout.html`, nav entry "Prestaties", and a link "Prestatie-analyse" on the existing `/activity/{id}` page to `/performance/run/{id}` (P3-8; the only change to an existing template besides the nav). Text in Dutch. Charts with the Chart.js already used |
| config | `GarminProperties` gets a nested `performance` block (`garmin.performance.*`): warm-up minutes (10), minimum steady window minutes (30), speed CV limit (0.10), elevation limit m/km (15), minimum run counts of section 2.4, stratum zone set default. Defaults are the PROPOSAL values |

No new table, no schema change, no Strava column change. Results are computed on demand (same approach as Phase 2, nothing derived is persisted, so an algorithm change needs no migration). Logging: counts and the algorithm version only, no HR values, no coordinates, no activity names (spec 4.1).

Computation cost: a stream is stored as JSON text, so an overview over every run reads and parses every stream. The build measures the time on the production copy. If the overview takes more than 5 seconds even with the cache, the build stops and the owner decides whether a persisted per-run result table is acceptable (question P3-7).

## 6. AI prompt

Phase 3 changes no prompt by default (P3-10). Strava stream-derived values are not health data of the Garmin kind, but the rule "the app's AI stays copy-paste only" is unchanged. If the owner wants a `<prestatie>` block (like `?herstel=true`) it is added behind an explicit option, with its own guardrail line.

## 7. Tests (offline, hand-computed)

Core tests in `core/src/test`, service and controller tests in `runner/src/test`. All streams are synthetic and built in code (shape only, no real identifiers or coordinates).

| Area | Cases |
|---|---|
| `CleanRun` | removes `dt <= 0`, `dt > 30`, HR 39 and 231, speed above 12.5 m/s, speed below 0.5 m/s; 30 identical HR values dropped, 29 kept; 89 % HR coverage abstains, 90 % ok; unequal stream lengths use the shortest; non-1 Hz streams (3 s steps) give the same averages as 1 Hz |
| `SteadyRun` | each failing criterion alone excludes (short window 29 min against 30; block speed CV 11 % against 9 %; elevation 16 against 14 m/km; interval and tempo sessions; race); the exclusion reason is reported |
| EF index and trend (#16, #17) | EF of a constant 3.0 m/s at 150 bpm is `3 / 150 = 0.02` (1.2 m per beat); warm-up excluded; HR zone strata not pooled; trend verdict cases `IMPROVED`, `DECLINED`, `STABLE`, `UNCERTAIN`, `INSUFFICIENT_DATA` with 4 against 5 runs; temperature gap above 8 degrees C caps the verdict at `UNCERTAIN` |
| Drift and decoupling (#18, #19) | by hand: 30 min window, `H1` 150 bpm at 3.0 m/s, `H2` 156 bpm at 3.0 m/s: drift 6 bpm = 4.0 %, decoupling `(0.0200 - 0.019231) / 0.0200 = 3.85 %`; speed ratio 1.06 makes drift unavailable while decoupling is still computed; negative drift (HR falls); flat series; 4 qualifying runs abstain |
| Critical speed (#22) | efforts generated from CS 4.0 m/s and D' 200 m at 3, 6 and 12 min (`d = 4.0 t + 200`: 920 m in 180 s, 1640 m in 360 s, 3080 m in 720 s) recover CS and D' exactly; 2 anchors abstain; duration ratio below 2 abstains; noisy data gives `R2` below 0.98 and abstains; leave-one-out range; temporal validation uses only earlier anchors (a later anchor does not change an earlier prediction) |
| Zones (#24, #40) | time in zones by hand with 3 s sample gaps; zone boundaries (floor value belongs to the higher zone, as in `HrZones`); Garmin floors against app zones are not mixed; week boundaries on activity-local dates; classes easy, moderate, hard add up to 100 %; periods with fewer than 3 runs carry the warning |
| Durability and long run (#25, #26) | block EF by hand on a 90 min synthetic run with EF falling 5 % per hour; run shorter than 75 min abstains; a block with 79 % valid samples is skipped; terrain difference between thirds gives `onzeker`; long run definition (workout type 2, and 75 min with 1.5x median); cadence in spm |
| Intervals (#27) | 6 x 1 km with known paces: fade, CV and HR rise by hand; 2 reps give a list without statistics; different rep signatures are not pooled; a reclassified (non interval) session produces nothing |
| HR recovery (#28) | `HRR60` by hand; protocol classes stay separate; `HRend` below 85 % of max HR rejected; gap of 6 s rejected; metric unavailable when the median sample interval exceeds 5 s |
| Power (#29) | ratios by hand; `device_watts` false excluded; coverage below 70 % unavailable |
| GAP (#30) | Minetti factor at 0 %, +10 % (1.658, `5.968 / 3.6`) and -10 % by hand; grade beyond 20 % ignored; run with more than 5 % extreme grade unavailable; missing stream unavailable; terrain class boundaries (4.9, 5.0, 15.0 m/km) |
| Finder (#32) | each hard criterion excludes alone; soft criteria shown with `onbekend` for missing temperature; fewer than 3 matches abstain with the main exclusion reason; ordering is deterministic; the output type has no score field |
| Fitness view (#21) | 4 indicators, 2 with verdict gives a summary; only 1 abstains the summary but shows the indicator; unfavourable and favourable directions counted correctly; no numeric score and no weights in the output type; indicators with different windows are not merged |
| Consistency (#46) | weeks with 0 runs counted; current week excluded; gap of 6 and 7 days; 7 weeks abstain, 8 weeks ok |
| Service and controller | `PerformanceService` with fake repositories; `GET /performance` and `/performance/run/{id}` render with data, without streams, without HR; JSON has no key or token; `NoStateChangingGetTest` covers the new controller; a test captures logging and asserts no HR value or coordinate from the fixtures appears |

Strava tests: none expected to change. If one must, the old and new values go in this document and in the progress log.

Temporal validation (spec rule 10): #22 has the walk-forward check above. The other metrics build no predictive model; baselines and windows use only runs before the reference date by construction (a test checks that a later run changes no earlier value).

## 8. Exit criteria (filled in at the end of the build)

| Criterion | Status |
|---|---|
| Design doc approved | APPROVED (owner, session 2026-10-30, written instruction) |
| Each metric has tests for normal, abstain and boundary cases | DONE: 86 new offline tests (core `strava/analysis/performance`, runner `PerformanceServiceTest`, `PerformanceControllerTest`); 315 tests in total, 0 failures |
| Each metric shows its sample counts, and the "association, not cause" note where it shows a relationship | DONE: every section carries counts and the note (`PerformanceText.ASSOCIATION`); the controller test checks the note and the safety line |
| Census (1.1) recorded and unavailable metrics named | DONE: section 11 |
| No health data, coordinates or identifiers in logs | DONE: `PerformanceService` logs one line with counts and the version; a test captures it and asserts no HR value, coordinate or activity name; start coordinates are excluded from the JSON (`@JsonIgnore`, tested) |
| Existing Strava tests pass unchanged | DONE: no existing Strava test changed (only `NoStateChangingGetTest` got the new controller added to its list) |
| Computation time (P3-7, limit 5 s) | DONE: first overview on a production copy 3.7 s, cached 0.2 to 0.3 s (530 runs, 303 streams). No persisted table needed |
| Owner approval of the phase | AWAITING APPROVAL |

## 9. Questions for the owner: ALL ANSWERED 2026-10-30 in session (record in `GARMIN_PROGRESS.md`)

| # | Question | Answer |
|---|---|---|
| P3-1 | HR zone set | Garmin `RUNNING` zones when present, app zones as fallback, `?zones=` switch. Old runs use today's zones |
| P3-2 | Steady-run definition and minimum counts | Keep, as config values |
| P3-3 | Trend rule (3 %, 2 standard errors, 8 degrees C cap) | Keep |
| P3-4 | Threshold as "kritische snelheid", Garmin lactate threshold HR beside it | Accepted |
| P3-5 | Power at activity level only, FIT storage stays Later | Accepted |
| P3-6 | Grade-adjusted pace with Minetti, app estimate | Accepted |
| P3-7 | On-demand with in-memory cache, no new table (stop and ask above 5 s) | Accepted |
| P3-8 | Pages `/performance`, `/performance/run/{id}`, `/performance/data`, nav "Prestaties"; link from the activity page | Yes, with the link |
| P3-9 | #21 in scope (owner request, section 3.4a). #20 | #21 in, #20 out |
| P3-10 | `<prestatie>` prompt block | No change to prompts |
| P3-11 | Three-class intensity grouping, no ideal distribution | Yes |

## 10. Not in Phase 3

Training load model, ATL, CTL, TRIMP (Phase 4); fatigue-adjusted performance #31 (Phase 4); training response; recovery linked to performance; race prediction changes (Phase 5); running economy proxy #20; VO2max (#23, UNAVAILABLE); FIT decoding or storage; writing to Garmin; any external AI call; any new Garmin endpoint call; any schema change.

## 11. Data census results

Run on 2026-10-30 on a production copy (read-only SQL counts, then a temporary database copy for the page measurement, both removed). Counts only, nothing committed.

| Item | Result |
|---|---|
| Running activities (Run, TrailRun, VirtualRun) | 530, from 2024-05-01 to 2026-10-11 |
| Trainer runs, manual or flagged runs | 0, 0 |
| Runs with laps | 530 |
| Runs with stored streams | 303 (all with `distance`, `time`, `altitude`, `grade_smooth`) |
| Runs with an HR stream | 259 (HR stream as long as the time stream in all 259) |
| Runs with a cadence stream | 217 |
| Runs with a `temp` stream | **0**. Temperature is unknown everywhere: the temperature confounder rules (EF cap, finder criterion) never fire on this data |
| Sample interval (mean per stream) | min 1.00 s, median 1.00 s, p90 1.01 s, max 6.39 s. Almost all streams are 1 Hz, so HR recovery (#28) is available (median interval limit 5 s) |
| Runs with `device_watts = true` and `average_watts` | 410 (all runs with power have it measured). Which of them are steady runs with a stream is in the page coverage line |
| Run duration | under 30 min: 38; 30 to 60 min: 274; 60 to 75 min: 110; 75 min or more: 108 |
| Runs in the last 90 days, and in the 90 days before | 47, and 64 (all with an HR stream) |
| Garmin zone rows stored | 2 (`DEFAULT`, `RUNNING`) |
| Steady runs found by the app (all history) | 119 of 530 (page "Databasis" shows the reasons: no stream 227, no HR stream 44, not a steady session 83, window too short 36, speed too variable 9, hilly 8, HR coverage 3, hard effort 1) |

Consequences for the owner's data: 227 runs (mostly the older ones) have no stored stream, so no stream metric can use them. Temperature based checks stay inactive. Durability (#25) has few qualifying runs (4 in the last 90 days at build time, minimum 5), so it abstains. HR recovery is available but needs 10 measurements per protocol class.

## 12. Build notes (tenth session, 2026-10-30)

- Code: core `com.running.strava.analysis.performance` (`Common`, `CleanRun`, `SteadyAndDrift` incl. `GradeAdjusted`, `Zones`, `EfIndex` incl. `FitnessView`, `Durability`, `Intervals` incl. `HeartRateRecovery`, `Power` incl. `CriticalSpeed`, `Comparable` incl. `Consistency`); `GarminRepository.findHeartRateZones()` (additive); runner `PerformanceService`, `PerformanceText`, `PerformanceController`, templates `performance.html`, `performance-run.html`, nav entry "Prestaties", link "Prestatie-analyse" on `/activity/{id}`; `garmin.performance.*` config (`zone-set` and the minimum counts, defaults = design values).
- Decisions the design left open (PROPOSAL, owner may change):
  - Window W and block cutting use the cumulative valid time (clock), not elapsed time, so pauses and dropped samples do not shift blocks. A 10 minute durability block counts when its valid time is at least 80 % of the elapsed time it spans.
  - "Minute 60" block = the 10 minute block that starts at minute 60 of valid time; durability needs 75 minutes of valid time in the run.
  - Heart rate zones: anything below the zone 1 floor counts as zone 1.
  - Trainer runs are excluded from the steady set (so from EF, drift, durability, GAP and critical speed anchors), even though zone distribution would allow them (0 trainer runs exist).
  - Differences (drift, durability change) use a mean difference with 2 standard errors and an absolute threshold (1 percentage point for drift, 3 for durability), because the EF rule works on percentages of a level.
  - #21 picks the zone group with the most runs for its EF indicator. The critical speed indicator compares the fit now with the fit of 90 days earlier (3 % threshold, overlapping leave-one-out ranges give "onzeker").
  - Critical speed walk-forward test set: every effort that is the fastest of its distance within the 90 days up to its own date, predicted from earlier efforts only.
  - HR recovery protocol class uses six 10 second sub-windows (staand: all below 0.5 m/s, wandelen: all below 2 m/s).
  - Intensity: three-class grouping per month; session mix per week (12) and per 4 weeks (13 blocks).
  - Grade share rule (more than 5 % beyond 25 %) is a share of valid time, not of samples.
- Not done: no persisted results, no schema change, no AI prompt change (P3-10), no new Garmin endpoint call, no FIT decoding.
- Not seen in a browser by the agent: pages were fetched as HTML only (status 200, texts present). Charts (Chart.js) and layout are unchecked visually.

## 13. Post-build changes after the owner's review (eleventh session, 2026-10-30)

All at the owner's written request in session. The metrics and formulas of sections 2 and 3 are unchanged unless listed here.
Algorithm version stays `performance-v1` (the classifier change below changes session types, see 13.4).

### 13.1 Redesign of `/performance` and `/performance/run/{id}` (owner: "niet overzichtelijk")

- Section order set by the owner: Conditietrend, Aerobe efficiëntie, Tijd in hartslagzones, Drift en ontkoppeling, then
  Kritische snelheid, Intensiteit, Regelmaat, Duurzaamheid, Lange runs, Intervallen (with HR recovery), Vermogen,
  "Welke runs tellen mee?" (was "Databasis"), Recente runs, Begrippen.
- Generic table renderer (`PSection`) replaced by typed view models: `PerformancePageBuilder` and `RunPageBuilder`
  (`PerformanceText.kt`, `PerformanceRunPage.kt`). Charts per section (EF dots per run plus monthly median with gaps,
  drift and decoupling dots with the 5 % rule-of-thumb line, weekly zone bars plus a 4-week zone bar, intensity 100 %
  bars, session mix bars, weekly km/runs for consistency, durability lines per run with the 100 % line, long run EF
  bars, repeated interval sessions' rep pace). Detailed tables folded away.
- EF displayed as metres per heartbeat (EF x 60), display only. The compare page keeps its own notation.
- EF section: zone groups without any runs are hidden with an explanation (steady runs are easy runs by definition, so
  zone 3 and 4+ never have steady runs); comparison shown as "ervoor -> recent" cards with date ranges and run counts.
- Run page: every measurement explained, with the owner's own median over recent steady runs as context
  (`TypicalValues`); comparable runs show the Strava name, the run itself as the first row, criteria as compact
  ✓/✗/? columns; criteria without data for every run (temperature) are hidden and named in the explanation.
- Help popovers (`?`) and a glossary (`PerformanceGlossary`) for hard terms (GAP, EF, drift, ontkoppeling,
  stroomdata/detailmetingen, hartslagdekking, ...). Dutch dates everywhere (`PerfDates`).
- Rules derived from this review for all future pages: `docs/ui-guidelines.md`.

### 13.2 Every period adjustable (owner request)

`PerformanceWindows` (runner): trend 90, EF short 28 and long 90, zone weeks 26, drift 90, critical speed 90, intensity
months 18, session mix weeks 52, consistency 12 and 52 weeks, durability 90, long runs 90, HR recovery 90, power 90 days;
run page "Jouw mediaan" 90 days. Defaults = the design values. URL parameters, clamped ranges, other choices kept.
Core: `CriticalSpeed.anchors/walkForward(windowDays)` and `Consistency.analyze(shortWeeks, longWeeks)` take parameters
with the old values as defaults. Note: zones per week now means the last N calendar weeks (was: last N weeks with data).
The long-run definition keeps its fixed 90-day median so the chosen window does not change which runs are long runs.

### 13.3 Missing streams: "Detaildata aanvullen" (dashboard)

Cause of the large gaps in the EF chart: 227 runs were stored without streams (rate limit during the full fetch), and
`/fetch-remaining` only fetches activities that are not stored at all. New use case `BackfillStreamsData`
(`POST /backfill-streams`, dashboard "Meer opties"): streams for stored runs without streams, newest first, one request per
run, stops cleanly at the Strava rate limit, continues on the next click, progress logged per run with an ETA.
`ActivityRepository.findIdsWithStreams()` added (id-only query in the adapter).

### 13.4 Interval classification: hilly auto-lap rule (owner approved after an impact list)

A trail with 1 km auto-laps (Kluisbos, 33 m/km) was classified "2 x 2000 m": lap pace followed the hills. Owner rejected
a heart-rate check (HR not always reliable). Rule in `LapClassifier`: more than 15 m climbing per km AND every lap except
the last exactly 1 km or 1 mile (±1.5 %) AND not a Strava workout -> no reps and no tempo. Elevation per km is passed
from every caller. Impact on the owner's 534 runs, shown before the change: 10 runs INTERVAL -> STEADY (all trails or a
hilly long run), 2 trails TEMPO -> STEADY, nothing else; hand-lapped hill intervals unchanged. Golden tests added.

### 13.5 Tests

330 offline tests pass (`:core:test :runner:test`, Java 23). No existing Strava test changed. Pages checked in a browser
(headless Chrome, real data on a second instance).
