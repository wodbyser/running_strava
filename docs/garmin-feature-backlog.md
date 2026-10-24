# Garmin Feature Backlog

**Status: not in scope for Phase 1 or Phase 2.** These are ideas, not requirements. Read `GARMIN_API.md` sections 2 and 3 first.

Each feature needs three things before it can be built:

1. The required Garmin data must be **VERIFIED** in the Gate 1 field inventory.
2. The formula, assumptions, and minimum data must be documented.
3. Validation must be defined (see `GARMIN_API.md` section 8).

Priority labels are the owner's ranking: **Very high**, **High**, **Medium**.

---

## A. Recovery and physiological health

| # | Feature | Description | Priority |
|---|---|---|---|
| 1 | Personal HRV baseline | Rolling HRV reference over 7, 28, 60 and 90 days | Very high |
| 2 | HRV trend analysis | Detects sustained rises, falls, and deviations from the personal norm | Very high |
| 3 | Resting heart rate analysis | Daily resting HR compared with the personal baseline | Very high |
| 4 | Recovery Readiness | Own recovery score from HRV, resting HR, sleep, Body Battery, and recent load. Must show components | Very high |
| 5 | Training response analysis | How physiological values respond to different sessions | Very high |
| 6 | Sleep quality analysis | Duration, regularity, interruptions, available stage estimates | Very high |
| 7 | Sleep debt | Estimated shortfall against personal sleep need. Cautious | High |
| 8 | Body Battery trends | Overnight recharge and daytime drain patterns | High |
| 9 | Autonomic stress analysis | Garmin stress values and their link to training and recovery | High |
| 10 | Recovery time estimation | Typical recovery duration after given session types, from history | Very high |
| 11 | Early fatigue detection | Combinations of signals that may indicate rising fatigue. No diagnosis | Very high |
| 12 | Illness pattern detection | Unusual physiological deviations, without medical diagnosis | Medium |
| 13 | Rest day effectiveness | How recovery indicators change after rest days | High |
| 14 | Deload effectiveness | Whether recovery weeks produce measurable recovery | Very high |
| 15 | Recovery trend forecast | Cautious short-term trend projection from own history | High |

**Example of interest:** HRV may be lowest about two days after hard interval sessions and barely change after easy long runs. Only report this with sample counts, confounder checks, and "association, not cause".

---

## B. Running fitness and performance development

| # | Feature | Description | Priority |
|---|---|---|---|
| 16 | Aerobic efficiency index | Speed relative to heart rate at comparable effort | Very high |
| 17 | Aerobic efficiency trends | Whether pace at the same HR improves over time | Very high |
| 18 | Heart rate drift analysis | HR drift in suitable steady runs | Very high |
| 19 | Aerobic decoupling | Speed or power to HR relationship, first half vs second half | Very high |
| 20 | Running economy proxy | Efficiency trends with pace, HR, and power if reliable | High |
| 21 | Fitness trend model | Combines several performance indicators into one tracked view | Very high |
| 22 | Threshold estimation | Estimated threshold pace and HR from suitable efforts, with uncertainty | Very high |
| 23 | VO2max trend validation | Compares Garmin VO2max estimates with independent performance indicators. **Only if VO2max is VERIFIED** | High |
| 24 | Heart rate zone distribution | Time in zones and changes in intensity mix | Very high |
| 25 | Durability analysis | How pace and HR change as a session gets longer | Very high |
| 26 | Long run fatigue analysis | Fatigue development during long runs | Very high |
| 27 | Interval performance analysis | Pace, HR response, and recovery across repeats | Very high |
| 28 | Heart rate recovery | HR recovery after efforts, if the detail data supports it | High |
| 29 | Power efficiency analysis | Power, speed, and HR, only with reliable power data | High |
| 30 | Terrain-adjusted performance | Corrects comparisons for gradient and route | High |
| 31 | Fatigue-adjusted performance | How accumulated fatigue affects performance | Very high |
| 32 | Comparable run finder | Historical runs similar in distance, terrain, intensity, and conditions | Very high |

**Example:** the same HR at different paces across months is a trend signal. Comparisons across different temperatures or terrain need adjustment or must be refused.

---

## C. Training load and adaptation

| # | Feature | Description | Priority |
|---|---|---|---|
| 33 | Internal training load | Load from duration and physiological intensity. Document the model (HR-based, TRIMP variant, or session RPE) | Very high |
| 34 | External training load | Distance, duration, elevation, pace, and power if available | Very high |
| 35 | Acute training load | Recent load | Very high |
| 36 | Chronic training load | Longer-term load | Very high |
| 37 | Fitness-fatigue model | Balance of accumulated stimulus and short-term fatigue. Validate before use | Very high |
| 38 | Training monotony | How evenly load is spread across days | High |
| 39 | Training strain | Total load combined with its variability | High |
| 40 | Intensity distribution | Easy, moderate, and hard session ratios | Very high |
| 41 | Training load progression | Rate of change in load | Very high |
| 42 | Recovery-adjusted load | Load interpreted with recovery data | Very high |
| 43 | Workout effectiveness | Whether a session type is followed by later performance gains | Very high |
| 44 | Optimal training frequency | Response to different numbers of sessions per week | High |
| 45 | Optimal long run duration | Long-run lengths the athlete tolerates well and those that cost recovery | High |
| 46 | Training consistency | Continuity, gaps, and regularity | High |
| 47 | Detraining analysis | Changes in fitness indicators during low-training periods | High |
| 48 | Training adaptation lag | Time between a training block and a measurable improvement | High |

**Caution:** do not let one formula decide how much to train. The acute:chronic workload ratio is not a validated injury predictor.

---

## D. Race preparation and prediction

| # | Feature | Description | Priority |
|---|---|---|---|
| 49 | Race performance prediction | Predicted times for 5 km, 10 km, half marathon, marathon, as ranges | Very high |
| 50 | Race readiness | Combines fitness, specificity, recovery, and recent performance. Fitness and short-term recovery kept separate | Very high |
| 51 | Goal feasibility analysis | How realistic a target is, given the data | Very high |
| 52 | Race pace recommendation | Target pace from current indicators | Very high |
| 53 | Pacing strategy generator | Pacing plans for different distances | High |
| 54 | Taper optimization | How the athlete responds to different tapers | Very high |
| 55 | Race day readiness check | Physiological state shortly before a race | Very high |
| 56 | Performance confidence interval | A range instead of one predicted time | Very high |
| 57 | Race simulation | Possible outcomes under different conditions | High |
| 58 | Post-race analysis | Predicted vs actual, with deviations explained | Very high |
| 59 | Race preparation retrospective | Training before good and poor races | Very high |

**Useful output for a predicted time** (for example 19:15 for 5 km): which sessions support it, how reliable it is, what is uncertain, whether recent sessions are specific enough, whether recovery is favourable, which sessions would add the most.

---

## E. Injury prevention and health

Build as a separate module. **Never** state that someone will get injured or is safe to train, based on a calculated score.

| # | Feature | Description | Priority |
|---|---|---|---|
| 60 | Load spike detection | Sudden jumps in distance, intensity, or elevation against personal history | Very high |
| 61 | Impact exposure tracking | Running time, distance, and other mechanical load indicators | Very high |
| 62 | Recovery deficit detection | Periods where recovery indicators move unfavourably | Very high |
| 63 | Injury history correlation | Training patterns before previously recorded injuries | Very high |
| 64 | Pain and symptoms journal | Location, severity, duration, and change of pain | Very high |
| 65 | Return-to-running monitor | Training build-up after injury or a break | Very high |
| 66 | Warning signal detection | Combines symptoms and load changes into descriptive warnings | Very high |
| 67 | Training tolerance analysis | Which session types the athlete tolerates | High |
| 68 | Bone stress exposure monitor | Relevant load patterns. No claim to measure bone stress or stress fractures | High |

**Rules:** pain, especially focal bone pain or pain when walking, outweighs any favourable recovery score. Flag serious symptoms for professional assessment.

---

## F. Advanced personal analytics

| # | Feature | Description | Priority |
|---|---|---|---|
| 69 | Personal physiological profile | Individual profile of recovery, load, and performance development | Very high |
| 70 | Training responder analysis | Which session types the athlete responds to best | Very high |
| 71 | Recovery pattern learning | Learns the athlete's recovery patterns | Very high |
| 72 | Performance anomaly detection | Unusually good or poor sessions | Very high |
| 73 | Contextual performance explanation | Possible explanations for deviations, labelled as possibilities | Very high |
| 74 | Historical similarity search | Past periods with similar load and recovery | Very high |
| 75 | Training block comparison | Blocks compared on performance, load, and recovery | Very high |
| 76 | Personal best conditions | Conditions under which best performances occurred | High |
| 77 | Adaptation efficiency | Performance change against preceding load | High |
| 78 | Longitudinal athlete model | Evolving model of fitness and physiological response. Separate slow variables (aerobic fitness, durability, adaptation) from fast ones (fatigue, HRV deviation, sleep, acute recovery) | Very high |
| 79 | Explainable AI insights | Analyses with references to the underlying data | Very high |
| 80 | Automated weekly coaching review | Weekly review of training, fitness, and recovery | Very high |
| 81 | Session-type readiness | Readiness per session type (easy run, tempo, intervals, long run, race pace). Shows, for each type, whether recent recovery and load support it, with components shown. Built from the app's own data, not a Garmin score. Garmin Training Readiness is UNAVAILABLE until proven in the field inventory | Very high |

**Example:** "Intervals: low readiness (resting HR 4 bpm above baseline, HRV below your 28-day norm, 2 hard sessions in 4 days). Easy run: high readiness." Each type must show its inputs, sample counts, and "association, not cause". Use this with rule 4 of `GARMIN_API.md` section 8: say "insufficient data" when history is thin.

---

## G. Longer-term project ideas

These are ideas only.

- **Personal athlete model.** A continuously updated view of aerobic efficiency, endurance, threshold, recovery, load, race readiness, with trend arrows. Model slow and fast variables separately. Start with simple, testable models. State-space or Bayesian time-series models only when enough data exists.
- **Automatic pattern discovery.** Searches for relationships such as "HRV stable at 55–70 km weeks" or "performance up after a recovery week". Requires minimum sample sizes, correction for confounders, and checks for chance findings. Otherwise it produces false findings.
- **Training scenario comparison.** Compares planned options (for example 65 km with one hard session, 50 km with two, 60 km with one hard session and more recovery) using the athlete's own history. Start as a scenario comparison, not a precise future prediction.
- **Multi-layer post-session analysis.**
  1. Execution: was the planned session carried out, and how consistent were pace and HR?
  2. Physiological response: HR behaviour, drift, comparison with similar sessions.
  3. Training context: fit with recent load, recovery state, and race preparation.
- **Insight engine.** Strava and Garmin data go through normalisation into an analytics engine, which splits into performance and recovery analytics, then an insight engine, then the AI coach. The analytics engine computes; the AI interprets. The AI must not invent measurements.

---

## H. Suggested order (owner's ranking, for later)

1. Garmin and Strava data fusion (foundation for everything)
2. Personal HRV and resting HR baseline
3. Training response and recovery analysis
4. Aerobic efficiency and heart rate drift
5. Personalised recovery readiness
6. Training load and recovery analysis
7. Longitudinal athlete model
8. Comparable workout engine
9. AI weekly coaching review
10. Race readiness and prediction enhancement

---

## I. Natural-language questions the AI layer should eventually answer

Each answer must cite the actual data and calculations, and say when evidence is insufficient.

- "Am I becoming aerobically fitter?"
- "Why was my heart rate unusually high during yesterday's run?"
- "Which training sessions require the most recovery?"
- "How has my HRV changed since increasing my weekly mileage?"
- "Compare my last ten easy runs."
- "How did my body respond to my previous recovery week?"
- "Am I sufficiently prepared for my next 5 km race?"
- "What were my best training blocks?"
- "What patterns preceded my strongest race performances?"
