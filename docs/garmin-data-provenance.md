# Garmin Data Provenance and Identity Matching

Gate 2 design. No code. Evidence: `garmin-capability-matrix.md` (sections 4, 7, 8), `garmin-field-inventory.md`, the Strava `Activity` model in `core`, and `GARMIN_API.md` sections 3 and 5. Statements not proven by a live response or source are marked UNVERIFIED.

## 1. Principles

1. Strava owns activity identity, distance, pace, route, laps, streams and best efforts. Garmin owns daily physiology. (`GARMIN_API.md` section 3.)
2. A Garmin activity is linked to a Strava activity only through the rules below. A wrong link is worse than no link.
3. An ambiguous match is never merged automatically. It goes to a review list.
4. Values from the two sources are never averaged. Where both exist, the UI and prompts show the source.
5. Every stored Garmin value keeps its lineage (section 6).

## 2. What identifies an activity on each side

| Side | Fields available (evidence) |
|---|---|
| Garmin (search and detail) | `activityId` (stable integer), `startTimeGMT` (seconds precision, UTC), `beginTimestamp` (epoch ms UTC), `startTimeLocal`, `distance` (m), `duration`, `elapsedDuration`, `movingDuration` (s), `activityType.typeKey` and `parentTypeId`, `manufacturer`, `isManualActivity`, start and end coordinates (absent for indoor), `activityUUID`. VERIFIED, matrix section 8. |
| Strava (`Activity` in core) | `id`, `startDate` (UTC), `distance` (m), `movingTime`, `elapsedTime` (s), `type`, `sportType`, `startLatlng`, `isManual`, `timezone`. |
| Not available today | Strava `external_id` and `device_name`: the current parser does not read them (`GARMIN_API.md` section 7). No Garmin field names a Strava ID (VERIFIED: none in 448 records or the Swagger document). |

## 3. Matching order

### Step 1: shared identifier (conditional, UNVERIFIED)

Strava's activity payload has an `external_id` field, which for uploads that came from a device or another service may contain the origin file or ID. Whether this account's Strava activities carry the Garmin activity ID in it is **not proven**. Plan:

- Gate 3 adds `external_id` and `device_name` to the Strava parser and persists them as new nullable columns (additive, no rename or drop; `ddl-auto: update`). Existing Strava rows get them on the next detail fetch or on a one-off backfill the owner triggers.
- A **dry-run report** then checks, for already time-matched pairs, whether `external_id` contains the Garmin `activityId`. Only if it does for essentially all matched pairs is step 1 enabled, as a rule "external ID contains the Garmin activity ID".
- Until proven, step 1 is off and step 2 is the only matcher.

### Step 2: start time, duration, distance, type

For each Garmin activity, find Strava candidates and classify.

**Type compatibility (required).** Garmin `typeKey` and Strava `sportType` (fall back to `type`) must map to the same family:

| Family | Garmin `typeKey` examples seen | Strava |
|---|---|---|
| run | `running`, other run subtypes (UNVERIFIED which exist) | `Run`, `TrailRun`, `VirtualRun` |
| walk | `walking` | `Walk`, `Hike` |
| ride | `cycling` | `Ride`, `VirtualRide`, `EBikeRide` |
| swim | `lap_swimming` | `Swim` |
| other | `other`, `rowing_v2`, `resort_skiing`, `assistance` | no automatic match |

Unmapped types are never matched automatically. The mapping lives in code as a table with a test.

**Component checks** (all measured from the data of the two records, none from a score):

| Component | Strong | Weak | Fail |
|---|---|---|---|
| Start time difference | at most 120 s | at most 600 s | over 600 s |
| Distance, relative to the larger value | at most 2 % | at most 5 % | over 5 % |
| Elapsed time (`elapsedDuration` vs `elapsedTime`), relative | at most 3 % or 10 s | at most 10 % | over 10 % |

Moving time is not used: Garmin and Strava compute it differently (UNVERIFIED, not compared). Coordinates are an optional tie-break: when a Garmin and a Strava start position both exist and are within 200 m, a candidate pair stays valid; beyond 2 km it fails. Indoor activities have no coordinates and skip this check.

**These thresholds are proposals, not measured values.** Nothing in Gate 1 compared the two sources on real pairs. Gate 3 must run the matcher in report-only mode on the existing Strava history (read-only, no writes) and show the distribution of the three differences before the thresholds are accepted. The owner approves final thresholds from that report.

**Result per Garmin activity** (a tier, not a numeric score, in line with `GARMIN_API.md` section 8 rule 6):

| Tier | Rule |
|---|---|
| `MATCHED` | Exactly one compatible candidate, all three components Strong, and that Strava activity has no other compatible Garmin candidate. |
| `REVIEW` | Any of: one candidate with a Weak component; two or more candidates; one Strava activity claimed by two Garmin activities. |
| `UNMATCHED` | No candidate within the Weak limits. Normal for Garmin-only activities (walks, other sports) and for Strava-only activities. |

The row stores the component differences so the reason for the tier is visible.

### Step 3: otherwise no match

No link is created. The Garmin activity is stored as an activity reference only.

## 4. Review list and manual decisions

- `REVIEW` rows are listed in `GET /garmin/status` as a count and in a read-only JSON list `GET /garmin/matches/review` (activity IDs, start times, the component differences, the candidate Strava IDs).
- Decisions (confirm one candidate, reject all) change state, so they would be POST endpoints. **Whether Phase 1 includes them is an owner decision** (open question in `GARMIN_PROGRESS.md`). Without them, `REVIEW` rows simply stay unlinked and nothing downstream uses them.
- A confirmed or rejected decision is persistent. A later sync never overwrites it. A `MATCHED` row is recomputed only if either source activity changes materially, and a change in the result moves it to `REVIEW`, never silently to another Strava activity.
- Strava activities are edited and deleted after the fact (the Strava sync rechecks 30 days). When a linked Strava activity disappears, the link goes to `UNMATCHED` with the reason recorded.

## 5. Where matched data is used

| Use | Rule |
|---|---|
| FIT file time series | Only for `MATCHED` pairs, and only after a separate owner decision (Gate 3 open point). Never for `REVIEW` or `UNMATCHED`. |
| Garmin training effect and load on an activity | Shown next to the Strava activity as Garmin values, labelled with the source. Not used in Strava-based calculations. |
| Daily physiology (HRV, sleep, resting HR, Body Battery, stress) | Keyed by calendar date. Needs no activity match. |

## 6. Provenance per stored value

Every Garmin row stores (`GARMIN_API.md` section 4.3):

| Column | Meaning |
|---|---|
| `source_endpoint` | Which endpoint returned it (the path template, no query string). |
| `source_timestamp` | When Garmin produced it, if the payload has one (UTC). Otherwise null. |
| `synced_at` | When the app stored it (UTC). |
| `calendar_date` | The date the value belongs to, as Garmin defines it: sleep and HRV by wake-up date, others by local day. |
| `original_unit` | Unit as returned (see the inventory), or `UNVERIFIED`. |
| `raw_payload_id` | Link to the raw JSON row when raw storage is on. |

Derived values (Phase 2 onward) add `method`, `algorithm_version` and the input window.

## 7. Per-field source precedence

| Data | Source | Notes |
|---|---|---|
| Activity identity, start, distance, moving and elapsed time, pace | Strava | Garmin copy is kept only as the activity reference. |
| Laps, streams, best efforts, PBs | Strava | Garmin laps and FIT are a later option for matched pairs. |
| Activity average and max heart rate | Strava value is the one used by existing analytics. The Garmin value is stored as a separate Garmin field. | Never averaged. A difference between them is shown with sources. |
| Resting heart rate | Garmin daily value when present, else the manual `strava_tokens.resting_hr`, else none. The source is shown. | `GARMIN_API.md` section 3. |
| Max heart rate, HR zones | Existing app logic stays. Garmin zones (bpm, per sport) are stored as reference data and labelled `[gemeten]` as Garmin's setting, not as the app's estimate. | The existing estimate and Garmin's configured value can differ. Show both. |
| Training effect, activity training load | Garmin only. Proprietary, formula and unit UNVERIFIED. Labelled as a Garmin estimate. | |
| Sleep, HRV, Body Battery, stress, steps | Garmin only. Sleep stages, Body Battery and stress are proprietary estimates. | |
| Sleep need | Owner's target of 7 h 30 total sleep per night (owner decision 2026-10-25). Garmin's own `sleepNeed` is stored as a separate Garmin field. | Sleep debt uses total sleep only. Deep sleep is analysed separately (Phase 2). |

## 8. Conflicts and quality rules

- **Same quantity, two sources:** show both with the source. Do not pick silently. Do not average.
- **Time zone:** Strava `startDate` is UTC, Garmin `startTimeGMT` is UTC. Comparisons use UTC only. Local-time display follows audit decision Q4. The Garmin activity time zone name can differ from the app's (`Europe/Paris` seen, the app uses `Europe/Brussels` for "yesterday"; same offsets in practice).
- **Daylight saving time:** Garmin `...Local` epoch fields are UTC-shifted numbers, not instants (matrix section 8, inventory). Never compare them with Strava.
- **Manual activities:** if Strava `isManual` or Garmin `isManualActivity` is true, the pair needs Strong on all three components and is always put in `REVIEW`, not `MATCHED`.
- **Duplicates on the Garmin side:** two Garmin activities that overlap the same Strava activity go to `REVIEW` (for example a split or a re-upload).
- **Missing values** are stored as null with the reason. Never defaulted to 0.

## 9. Privacy

- Coordinates are used only for the optional tie-break, kept in the local database like Strava coordinates, never logged, never in fixtures.
- Owner decisions (2026-10-25): raw activity payloads drop profile image URLs; the owner name field is kept.
- Health data never goes to an external AI provider.

## 10. Tests required in Gate 3

Offline, with fixtures from `runner/src/test/resources/garmin/`:

1. One compatible candidate, all Strong: `MATCHED`.
2. Start 5 minutes off: `REVIEW` (Weak).
3. Two candidates within limits: `REVIEW`, nothing linked.
4. One Strava activity claimed by two Garmin activities: `REVIEW` for both.
5. Distance 6 % off or start 11 minutes off: `UNMATCHED`.
6. Type mismatch (run vs ride): `UNMATCHED`. Unmapped type (`other`): never matched.
7. Manual activity: never `MATCHED`.
8. Confirmed and rejected decisions survive a re-run.
9. A removed Strava activity clears the link with a recorded reason.
10. Idempotent: running the matcher twice changes nothing.
11. Report-only mode writes nothing.

## 11. Owner decisions (2026-10-25)

1. Match review in Phase 1 is a **read-only list**. No confirm or reject POST endpoints in Phase 1. Sections 4 and 10 items 8 apply to a later phase (decisions persist only if endpoints are added later).
2. A report-only dry run in Gate 3 comes before the thresholds are fixed. The owner approves final thresholds from that report.
3. Two nullable Strava columns (`external_id`, `device_name`) may be added in Gate 3 (additive only).

## 12. Dry-run result and owner decisions (2026-10-25, second Gate 3 session)

Owner decisions: matching stays on demand in Phase 1; type mapping stays as is (run, walk, ride, swim); position is a supporting component only (it can move a pair to REVIEW, never reject or link it); thresholds stay provisional until the owner reviews the report below.

**Report-only dry run** on a copy of the production database (448 Garmin activities synced from the live API, 585 Strava activities, 451 of them in the Garmin period). Nothing was written, no match stored, no Strava row changed.

| Result | Value |
|---|---|
| Tiers | MATCHED 440, REVIEW 0, UNMATCHED 8 |
| Start difference of the nearest compatible pair (441 pairs within 1 h) | min 0 s, median 0 s, p90 0 s, max 40 s (Strong limit 120 s, Weak 600 s) |
| Distance difference (percent of the larger value) | median 0.0004 %, p90 0.02 %, max 3.14 % (Strong limit 2 %, Weak 5 %) |
| Elapsed-time difference (percent) | median 0.009 %, p90 0.029 %, max 33.5 % (Strong limit 3 % or 10 s, Weak 10 %) |
| Strava `external_id` present among time-matched pairs | 0 of 441 (the column was empty in the copy because no Strava fetch was made). Step 1 (shared ID) stays UNVERIFIED and off |

The 8 UNMATCHED Garmin activities: 1 run without any Strava activity within 1 hour; 2 walks without a Strava activity within 1 hour; 1 `resort_skiing`, 1 `assistance`, 1 `other`, 1 `rowing_v2` (unmapped types, never matched); 1 run with the same start time as a Strava run, distance 3.1 % apart and elapsed time 33.5 % apart (586 s), which fails the elapsed limit. Nothing fell in the Weak band for start time, so REVIEW is empty. Strava activities in the Garmin period without any Garmin activity within 10 minutes: 8 of 451. Strava activities before the first Garmin activity: 134.

Reading for the owner: on this history every start difference is at most 40 s and almost every distance and elapsed difference is far inside the Strong limits, so the proposed limits are loose compared with the observed data. This is evidence for one account and one device and is not a reason to tighten anything by itself. Thresholds stay as proposed until the owner decides.
