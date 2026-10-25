# Garmin Field Inventory

Gate 1 output. Source: live responses from 2026-10-25 (7-day window 2026-10-18 to 2026-10-24, plus wider samples for nullability) and the official Swagger document (`openapi 3.0.1`, version `1.0.0`, embedded in the Garmin developer page, see the capability matrix).

**How to read this file**

- `Live type`: JSON types seen in live responses. `null seen` counts how often the path was `null` out of how often it appeared. A path that never appears in a live response is listed in the Swagger-only section of its endpoint (UNVERIFIED: documented but never returned for this account).
- `Swagger description`: copied from the Swagger schema when present. Where it names a unit, the unit is **DOCUMENTED**. Units marked **OBSERVED** were inferred from live values (see section "Units and semantics"). Everything else is **UNVERIFIED**.
- No real values or identifiers are in this file.

Sample sizes (objects per endpoint) are given in each heading.


## 1 Stress daily

`GET /usersummary-service/stats/stress/daily/{start}/{end}`  | sample: 50 record(s) from 3 response(s)  | schema: `MultiValueWellnessChartItemDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `calendarDate` | str | 0/50 |  |
| `values` | object | 0/50 | A map of stress metric names (overallStressLevel, highStressDuration, mediumStressDuration, lowStressDuration, and restStressDuration) to th |
| `values.highStressDuration` | int | 0/50 |  |
| `values.lowStressDuration` | int | 0/50 |  |
| `values.overallStressLevel` | int | 0/50 |  |
| `values.restStressDuration` | int | 0/50 |  |
| `values.mediumStressDuration` | int | 0/50 |  |

## 2 Steps daily

`GET /usersummary-service/stats/steps/daily/{start}/{end}`  | sample: 22 record(s) from 2 response(s)  | schema: `StepsDailyStatDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `calendarDate` | str | 0/22 |  |
| `totalSteps` | int | 0/22 | Total steps taken for the day |
| `totalDistance` | int | 0/22 | Total step distance in meters |
| `stepGoal` | int | 0/22 | User's step goal for the day |

## 8 Sleep (day)

`GET /sleep-service/sleep/dailySleep?date`  | sample: 1 record(s) from 1 response(s)  | schema: `DailySleepDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `id` | int | 0/1 |  |
| `userProfilePK` | int | 0/1 |  |
| `calendarDate` | str | 0/1 | Calendar date the sleep is associated with, in yyyy-MM-dd format. |
| `sleepTimeSeconds` | int | 0/1 | Total measured sleep time in seconds, excluding naps. |
| `napTimeSeconds` | int | 0/1 | Sum, in seconds, of all naps recorded for this calendar date. |
| `sleepWindowConfirmed` | bool | 0/1 | Whether the sleep window has been confirmed. |
| `sleepWindowConfirmationType` | str | 0/1 | Type of the sleep window was confirmed. One of: unconfirmed, device_confirmed, manually_confirmed, auto_confirmed, auto_confirmed_final, off |
| `sleepStartTimestampGMT` | int | 0/1 | Start of the confirmed/final sleep window, in UTC, in ms. |
| `sleepEndTimestampGMT` | int | 0/1 | End of the confirmed/final sleep window, in UTC, in ms. |
| `sleepStartTimestampLocal` | int | 0/1 | Start of the confirmed/final sleep window, in user's local timezone offset, in ms. |
| `sleepEndTimestampLocal` | int | 0/1 | End of the confirmed/final sleep window, in user's local timezone offset, in ms. |
| `autoSleepStartTimestampGMT` | null only | 1/1 | Start of the auto-detected sleep window. |
| `autoSleepEndTimestampGMT` | null only | 1/1 | End of the auto-detected sleep window. |
| `sleepQualityTypePK` | null only | 1/1 | Sleep quality classification key (poor, average, good, excellent). |
| `sleepResultTypePK` | null only | 1/1 | Sleep result classification key: 'bad', 'ok', or 'great'. |
| `unmeasurableSleepSeconds` | int | 0/1 | Duration in seconds of sleep that could not be classified into deep, light, REM, or awake levels. |
| `deepSleepSeconds` | int | 0/1 | Duration in seconds spent in deep sleep. |
| `lightSleepSeconds` | int | 0/1 | Duration in seconds spent in light sleep. |
| `remSleepSeconds` | int | 0/1 | Duration in seconds spent in REM sleep. |
| `awakeSleepSeconds` | int | 0/1 | Duration in seconds spent awake during the sleep window. |
| `deviceRemCapable` | bool | 0/1 | Whether the device used to capture this sleep is capable of detecting REM sleep. |
| `retro` | bool | 0/1 |  |
| `sleepFromDevice` | bool | 0/1 | Whether the sleep window/levels were computed by the device's sleep algorithm. |
| `averageRespirationValue` | float | 0/1 |  |
| `lowestRespirationValue` | float | 0/1 |  |
| `highestRespirationValue` | float | 0/1 |  |
| `awakeCount` | int | 0/1 | Number of times the user woke up during the sleep window. |
| `avgSleepStress` | float | 0/1 |  |
| `ageGroup` | str | 0/1 | Age group of the user. |
| `avgHeartRate` | float | 0/1 |  |
| `sleepScoreFeedback` | str | 0/1 | Feedback categorization of the sleep score. |
| `sleepScoreInsight` | str | 0/1 | Insight categorization associated with the sleep score. |
| `sleepScorePersonalizedInsight` | str | 0/1 | Personalized insight associated with the sleep score. |
| `sleepScores` | object | 0/1 |  |
| `sleepScores.totalDuration` | object | 0/1 |  |
| `sleepScores.totalDuration.qualifierKey` | str | 0/1 |  |
| `sleepScores.totalDuration.optimalStart` | float | 0/1 |  |
| `sleepScores.totalDuration.optimalEnd` | float | 0/1 |  |
| `sleepScores.stress` | object | 0/1 |  |
| `sleepScores.stress.qualifierKey` | str | 0/1 |  |
| `sleepScores.stress.optimalStart` | float | 0/1 |  |
| `sleepScores.stress.optimalEnd` | float | 0/1 |  |
| `sleepScores.awakeCount` | object | 0/1 |  |
| `sleepScores.awakeCount.qualifierKey` | str | 0/1 |  |
| `sleepScores.awakeCount.optimalStart` | float | 0/1 |  |
| `sleepScores.awakeCount.optimalEnd` | float | 0/1 |  |
| `sleepScores.overall` | object | 0/1 |  |
| `sleepScores.overall.value` | int | 0/1 |  |
| `sleepScores.overall.qualifierKey` | str | 0/1 |  |
| `sleepScores.remPercentage` | object | 0/1 |  |
| `sleepScores.remPercentage.value` | int | 0/1 |  |
| `sleepScores.remPercentage.qualifierKey` | str | 0/1 |  |
| `sleepScores.remPercentage.optimalStart` | float | 0/1 |  |
| `sleepScores.remPercentage.optimalEnd` | float | 0/1 |  |
| `sleepScores.remPercentage.idealStartInSeconds` | float | 0/1 |  |
| `sleepScores.remPercentage.idealEndInSeconds` | float | 0/1 |  |
| `sleepScores.restlessness` | object | 0/1 |  |
| `sleepScores.restlessness.qualifierKey` | str | 0/1 |  |
| `sleepScores.restlessness.optimalStart` | float | 0/1 |  |
| `sleepScores.restlessness.optimalEnd` | float | 0/1 |  |
| `sleepScores.lightPercentage` | object | 0/1 |  |
| `sleepScores.lightPercentage.value` | int | 0/1 |  |
| `sleepScores.lightPercentage.qualifierKey` | str | 0/1 |  |
| `sleepScores.lightPercentage.optimalStart` | float | 0/1 |  |
| `sleepScores.lightPercentage.optimalEnd` | float | 0/1 |  |
| `sleepScores.lightPercentage.idealStartInSeconds` | float | 0/1 |  |
| `sleepScores.lightPercentage.idealEndInSeconds` | float | 0/1 |  |
| `sleepScores.deepPercentage` | object | 0/1 |  |
| `sleepScores.deepPercentage.value` | int | 0/1 |  |
| `sleepScores.deepPercentage.qualifierKey` | str | 0/1 |  |
| `sleepScores.deepPercentage.optimalStart` | float | 0/1 |  |
| `sleepScores.deepPercentage.optimalEnd` | float | 0/1 |  |
| `sleepScores.deepPercentage.idealStartInSeconds` | float | 0/1 |  |
| `sleepScores.deepPercentage.idealEndInSeconds` | float | 0/1 |  |
| `sleepVersion` | int | 0/1 |  |
| `sleepNeed` | object | 0/1 | Sleep need information for the current night. |
| `sleepNeed.userProfilePk` | int | 0/1 |  |
| `sleepNeed.calendarDate` | str | 0/1 |  |
| `sleepNeed.deviceId` | int | 0/1 |  |
| `sleepNeed.timestampGmt` | str | 0/1 |  |
| `sleepNeed.baseline` | int | 0/1 |  |
| `sleepNeed.actual` | int | 0/1 |  |
| `sleepNeed.feedback` | str | 0/1 |  |
| `sleepNeed.trainingFeedback` | str | 0/1 |  |
| `sleepNeed.sleepHistoryAdjustment` | str | 0/1 |  |
| `sleepNeed.hrvAdjustment` | str | 0/1 |  |
| `sleepNeed.napAdjustment` | str | 0/1 |  |
| `sleepNeed.displayedForTheDay` | bool | 0/1 |  |
| `sleepNeed.preferredActivityTracker` | bool | 0/1 |  |
| `nextSleepNeed` | object | 0/1 | Sleep need information for the upcoming night. |
| `nextSleepNeed.userProfilePk` | int | 0/1 |  |
| `nextSleepNeed.calendarDate` | str | 0/1 |  |
| `nextSleepNeed.deviceId` | int | 0/1 |  |
| `nextSleepNeed.timestampGmt` | str | 0/1 |  |
| `nextSleepNeed.baseline` | int | 0/1 |  |
| `nextSleepNeed.actual` | int | 0/1 |  |
| `nextSleepNeed.feedback` | str | 0/1 |  |
| `nextSleepNeed.trainingFeedback` | str | 0/1 |  |
| `nextSleepNeed.sleepHistoryAdjustment` | str | 0/1 |  |
| `nextSleepNeed.hrvAdjustment` | str | 0/1 |  |
| `nextSleepNeed.napAdjustment` | str | 0/1 |  |
| `nextSleepNeed.displayedForTheDay` | bool | 0/1 |  |
| `nextSleepNeed.preferredActivityTracker` | bool | 0/1 |  |

Swagger-only top-level fields never seen live (UNVERIFIED): `averageSpO2Value`, `lowestSpO2Value`, `highestSpO2Value`, `numberOfEventsBelowThreshold`, `averageSpO2HRSleep`, `spo2Threshold`, `userNote`, `kidSleepNote`, `dailyNapDTOS`, `breathingDisruptionSeverity`, `sleepAlignment`

## 9 Sleep summaries

`GET /sleep-service/sleep/dailySleepSummariesByDate`  | sample: 70 record(s) from 3 response(s)  | schema: `DailySleepDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `id` | int | 0/70 |  |
| `userProfilePK` | int | 0/70 |  |
| `calendarDate` | str | 0/70 | Calendar date the sleep is associated with, in yyyy-MM-dd format. |
| `sleepTimeSeconds` | int | 0/70 | Total measured sleep time in seconds, excluding naps. |
| `napTimeSeconds` | int | 0/70 | Sum, in seconds, of all naps recorded for this calendar date. |
| `sleepWindowConfirmed` | bool | 0/70 | Whether the sleep window has been confirmed. |
| `sleepWindowConfirmationType` | str | 0/70 | Type of the sleep window was confirmed. One of: unconfirmed, device_confirmed, manually_confirmed, auto_confirmed, auto_confirmed_final, off |
| `sleepStartTimestampGMT` | int | 0/70 | Start of the confirmed/final sleep window, in UTC, in ms. |
| `sleepEndTimestampGMT` | int | 0/70 | End of the confirmed/final sleep window, in UTC, in ms. |
| `sleepStartTimestampLocal` | int | 0/70 | Start of the confirmed/final sleep window, in user's local timezone offset, in ms. |
| `sleepEndTimestampLocal` | int | 0/70 | End of the confirmed/final sleep window, in user's local timezone offset, in ms. |
| `autoSleepStartTimestampGMT` | null only | 70/70 | Start of the auto-detected sleep window. |
| `autoSleepEndTimestampGMT` | null only | 70/70 | End of the auto-detected sleep window. |
| `sleepQualityTypePK` | null only | 70/70 | Sleep quality classification key (poor, average, good, excellent). |
| `sleepResultTypePK` | null only | 70/70 | Sleep result classification key: 'bad', 'ok', or 'great'. |
| `unmeasurableSleepSeconds` | int | 0/70 | Duration in seconds of sleep that could not be classified into deep, light, REM, or awake levels. |
| `deepSleepSeconds` | int | 0/70 | Duration in seconds spent in deep sleep. |
| `lightSleepSeconds` | int | 0/70 | Duration in seconds spent in light sleep. |
| `remSleepSeconds` | int | 0/70 | Duration in seconds spent in REM sleep. |
| `awakeSleepSeconds` | int | 0/70 | Duration in seconds spent awake during the sleep window. |
| `deviceRemCapable` | bool | 0/70 | Whether the device used to capture this sleep is capable of detecting REM sleep. |
| `retro` | bool | 0/70 |  |
| `sleepFromDevice` | bool | 0/70 | Whether the sleep window/levels were computed by the device's sleep algorithm. |
| `averageRespirationValue` | float | 0/70 |  |
| `lowestRespirationValue` | float | 0/70 |  |
| `highestRespirationValue` | float | 0/70 |  |
| `awakeCount` | int | 0/70 | Number of times the user woke up during the sleep window. |
| `avgSleepStress` | float | 0/70 |  |
| `ageGroup` | str | 0/70 | Age group of the user. |
| `avgHeartRate` | float | 0/70 |  |
| `sleepScoreFeedback` | str | 0/70 | Feedback categorization of the sleep score. |
| `sleepScoreInsight` | str | 0/70 | Insight categorization associated with the sleep score. |
| `sleepScorePersonalizedInsight` | str | 0/70 | Personalized insight associated with the sleep score. |
| `sleepScores` | object | 0/70 |  |
| `sleepScores.totalDuration` | object | 0/70 |  |
| `sleepScores.totalDuration.qualifierKey` | str | 0/70 |  |
| `sleepScores.totalDuration.optimalStart` | float | 0/70 |  |
| `sleepScores.totalDuration.optimalEnd` | float | 0/70 |  |
| `sleepScores.stress` | object | 0/70 |  |
| `sleepScores.stress.qualifierKey` | str | 0/70 |  |
| `sleepScores.stress.optimalStart` | float | 0/70 |  |
| `sleepScores.stress.optimalEnd` | float | 0/70 |  |
| `sleepScores.awakeCount` | object | 0/70 |  |
| `sleepScores.awakeCount.qualifierKey` | str | 0/70 |  |
| `sleepScores.awakeCount.optimalStart` | float | 0/70 |  |
| `sleepScores.awakeCount.optimalEnd` | float | 0/70 |  |
| `sleepScores.overall` | object | 0/70 |  |
| `sleepScores.overall.value` | int | 0/70 |  |
| `sleepScores.overall.qualifierKey` | str | 0/70 |  |
| `sleepScores.remPercentage` | object | 0/70 |  |
| `sleepScores.remPercentage.value` | int | 0/70 |  |
| `sleepScores.remPercentage.qualifierKey` | str | 0/70 |  |
| `sleepScores.remPercentage.optimalStart` | float | 0/70 |  |
| `sleepScores.remPercentage.optimalEnd` | float | 0/70 |  |
| `sleepScores.remPercentage.idealStartInSeconds` | float | 0/70 |  |
| `sleepScores.remPercentage.idealEndInSeconds` | float | 0/70 |  |
| `sleepScores.restlessness` | object | 0/70 |  |
| `sleepScores.restlessness.qualifierKey` | str | 0/70 |  |
| `sleepScores.restlessness.optimalStart` | float | 0/70 |  |
| `sleepScores.restlessness.optimalEnd` | float | 0/70 |  |
| `sleepScores.lightPercentage` | object | 0/70 |  |
| `sleepScores.lightPercentage.value` | int | 0/70 |  |
| `sleepScores.lightPercentage.qualifierKey` | str | 0/70 |  |
| `sleepScores.lightPercentage.optimalStart` | float | 0/70 |  |
| `sleepScores.lightPercentage.optimalEnd` | float | 0/70 |  |
| `sleepScores.lightPercentage.idealStartInSeconds` | float | 0/70 |  |
| `sleepScores.lightPercentage.idealEndInSeconds` | float | 0/70 |  |
| `sleepScores.deepPercentage` | object | 0/70 |  |
| `sleepScores.deepPercentage.value` | int | 0/70 |  |
| `sleepScores.deepPercentage.qualifierKey` | str | 0/70 |  |
| `sleepScores.deepPercentage.optimalStart` | float | 0/70 |  |
| `sleepScores.deepPercentage.optimalEnd` | float | 0/70 |  |
| `sleepScores.deepPercentage.idealStartInSeconds` | float | 0/70 |  |
| `sleepScores.deepPercentage.idealEndInSeconds` | float | 0/70 |  |
| `sleepVersion` | int | 0/70 |  |
| `sleepNeed` | object | 0/70 | Sleep need information for the current night. |
| `sleepNeed.userProfilePk` | int | 0/70 |  |
| `sleepNeed.calendarDate` | str | 0/70 |  |
| `sleepNeed.deviceId` | int | 0/70 |  |
| `sleepNeed.timestampGmt` | str | 0/70 |  |
| `sleepNeed.baseline` | int | 0/70 |  |
| `sleepNeed.actual` | int | 0/70 |  |
| `sleepNeed.feedback` | str | 0/70 |  |
| `sleepNeed.trainingFeedback` | str | 0/70 |  |
| `sleepNeed.sleepHistoryAdjustment` | str | 0/70 |  |
| `sleepNeed.hrvAdjustment` | str | 0/70 |  |
| `sleepNeed.napAdjustment` | str | 0/70 |  |
| `sleepNeed.displayedForTheDay` | bool | 0/70 |  |
| `sleepNeed.preferredActivityTracker` | bool | 0/70 |  |
| `nextSleepNeed` | object | 0/70 | Sleep need information for the upcoming night. |
| `nextSleepNeed.userProfilePk` | int | 0/70 |  |
| `nextSleepNeed.calendarDate` | str | 0/70 |  |
| `nextSleepNeed.deviceId` | int | 0/70 |  |
| `nextSleepNeed.timestampGmt` | str | 0/70 |  |
| `nextSleepNeed.baseline` | int | 0/70 |  |
| `nextSleepNeed.actual` | int | 0/70 |  |
| `nextSleepNeed.feedback` | str | 0/70 |  |
| `nextSleepNeed.trainingFeedback` | str | 0/70 |  |
| `nextSleepNeed.sleepHistoryAdjustment` | str | 0/70 |  |
| `nextSleepNeed.hrvAdjustment` | str | 0/70 |  |
| `nextSleepNeed.napAdjustment` | str | 0/70 |  |
| `nextSleepNeed.displayedForTheDay` | bool | 0/70 |  |
| `nextSleepNeed.preferredActivityTracker` | bool | 0/70 |  |
| `dailyNapDTOS` | array | 0/3 | List of naps recorded on this calendar date. |
| `dailyNapDTOS[]` | object | 0/3 |  |
| `dailyNapDTOS[].userProfilePK` | int | 0/3 |  |
| `dailyNapDTOS[].deviceId` | int | 0/3 |  |
| `dailyNapDTOS[].calendarDate` | str | 0/3 |  |
| `dailyNapDTOS[].napTimeSec` | int | 0/3 |  |
| `dailyNapDTOS[].napStartTimestampGMT` | str | 0/3 | Start of the nap, in UTC. |
| `dailyNapDTOS[].napEndTimestampGMT` | str | 0/3 | End of the nap, in UTC. |
| `dailyNapDTOS[].napFeedback` | str | 0/3 |  |
| `dailyNapDTOS[].napSource` | int | 0/3 | Numeric code identifying how the nap was recorded: 0=AUTOMATIC (auto-detected by the device), 1=MANUAL_DEVICE (confirmed manually on the dev |
| `dailyNapDTOS[].napStartTimeOffset` | int | 0/3 | Timezone offset from UTC in minutes. |
| `dailyNapDTOS[].napEndTimeOffset` | int | 0/3 | Timezone offset from UTC in minutes. |
| `dailyNapDTOS[].createTimestampGMT` | null only | 3/3 |  |
| `dailyNapDTOS[].updateTimestampGMT` | null only | 3/3 |  |

Swagger-only top-level fields never seen live (UNVERIFIED): `averageSpO2Value`, `lowestSpO2Value`, `highestSpO2Value`, `numberOfEventsBelowThreshold`, `averageSpO2HRSleep`, `spo2Threshold`, `userNote`, `kidSleepNote`, `breathingDisruptionSeverity`, `sleepAlignment`

## 10 Body Battery

`GET /wellness-service/wellness/bodyBattery/reports/daily`  | sample: 39 record(s) from 3 response(s)  | schema: `BodyBatteryDailyReportDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `date` | str | 0/38 | Calendar date this Body Battery report is for. |
| `charged` | int | 0/38 | Amount of Body Battery charged during the day, on the 0-100 Body Battery scale. |
| `drained` | int | 0/38 | Amount of Body Battery drained during the day, on the 0-100 Body Battery scale. |
| `startTimestampGMT` | str | 0/38 | Start of the reporting window, in UTC |
| `endTimestampGMT` | str | 0/38 | End of the reporting window, in UTC. |
| `startTimestampLocal` | str | 0/38 | Start of the reporting window, in the user's local timezone. |
| `endTimestampLocal` | str | 0/38 | End of the reporting window, in the user's local timezone. |
| `bodyBatteryValuesArray` | array | 0/38 | Raw Body Battery readings for the day. |
| `bodyBatteryValuesArray[]` | array | 0/228 |  |
| `bodyBatteryValuesArray[][]` | int | 0/456 |  |
| `bodyBatteryValueDescriptorDTOList` | array | 0/38 | Column descriptors (index + key) describing the layout of each row in bodyBatteryValuesArray. |
| `bodyBatteryValueDescriptorDTOList[]` | object | 0/76 |  |
| `bodyBatteryValueDescriptorDTOList[].bodyBatteryValueDescriptorIndex` | int | 0/76 | Column index into each row of bodyBatteryValuesArray that this descriptor describes. |
| `bodyBatteryValueDescriptorDTOList[].bodyBatteryValueDescriptorKey` | str | 0/76 | Name of the column described by this descriptor: timestamp, bodyBatteryLevel, bodyBatteryStatus, or bodyBatteryVersion. |
| `bodyBatteryDynamicFeedbackEvent` | object | 0/38 |  |
| `bodyBatteryDynamicFeedbackEvent.eventTimestampGmt` | str | 0/38 |  |
| `bodyBatteryDynamicFeedbackEvent.bodyBatteryLevel` | str | 0/38 | Body Battery level (0-100). |
| `bodyBatteryDynamicFeedbackEvent.feedbackShortType` | str | 0/38 |  |
| `bodyBatteryDynamicFeedbackEvent.feedbackLongType` | str | 0/38 |  |
| `bodyBatteryActivityEvent` | array | 0/38 | List of factors that had an impact on Body Battery during the day. |
| `bodyBatteryActivityEvent[]` | object | 0/75 |  |
| `bodyBatteryActivityEvent[].eventType` | str | 0/75 | The type of Body Battery event - 'ACTIVITY', 'SLEEP', 'STRESS', 'RECOVERY', or 'NAP'. |
| `bodyBatteryActivityEvent[].eventStartTimeGmt` | str | 0/75 | Start time of the event, in UTC. |
| `bodyBatteryActivityEvent[].timezoneOffset` | int | 0/75 | Timezone offset from UTC, in ms. |
| `bodyBatteryActivityEvent[].durationInMilliseconds` | int | 0/75 |  |
| `bodyBatteryActivityEvent[].bodyBatteryImpact` | int | 0/75 |  |
| `bodyBatteryActivityEvent[].feedbackType` | str | 0/75 | Type of feedback/insight associated with this event. |
| `bodyBatteryActivityEvent[].shortFeedback` | str | 0/75 |  |
| `endOfDayBodyBatteryDynamicFeedbackEvent` | object | 0/38 |  |
| `endOfDayBodyBatteryDynamicFeedbackEvent.eventTimestampGmt` | str | 0/38 |  |
| `endOfDayBodyBatteryDynamicFeedbackEvent.bodyBatteryLevel` | str | 0/38 | Body Battery level (0-100). |
| `endOfDayBodyBatteryDynamicFeedbackEvent.feedbackShortType` | str | 0/38 |  |
| `endOfDayBodyBatteryDynamicFeedbackEvent.feedbackLongType` | str | 0/38 |  |

## 11 Daily summaries

`GET /usersummary-service/v2/dailysummaries`  | sample: 316 record(s) from 2 response(s)  | schema: `UserDailySummaryV2DTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `uuid` | str | 0/316 |  |
| `userProfilePk` | int | 0/316 |  |
| `calendarDate` | str | 0/316 |  |
| `source` | str | 0/316 | The programmatic source of this summary. Values are case insensitive. |
| `includesWellnessData` | bool | 0/316 | Whether or not wellness data is reflected in this user daily summary. |
| `includesActivityData` | bool | 0/316 | Whether or not activity data is reflected in this user daily summary. |
| `wellnessChronology` | object | 0/316 | Contains chronological metadata about the daily summary |
| `wellnessChronology.startTimestampGmt` | str | 0/316 | UTC/GMT timestamp of the first wellness data. |
| `wellnessChronology.startTimestampLocal` | str | 0/316 | User's local timestamp of the first wellness data. |
| `wellnessChronology.endTimestampGmt` | str | 0/316 | UTC/GMT timestamp of the last wellness data. |
| `wellnessChronology.endTimestampLocal` | str | 0/316 | User's local timestamp of the last wellness data. |
| `wellnessChronology.totalDurationInMillis` | int | 0/316 | Total duration of wellness data. |
| `movement` | object | 0/316 | Contains information about movement (i.e.: steps, wheelchair pushes, and floors ascended/descended). Fractional `value`s for floors ascended |
| `movement.steps` | object | 0/316 | Contains information about movement (e.g.: steps, wheelchair pushes, floors ascended/descended). |
| `movement.steps.goal` | int | 0/316 | User's daily goal for this type of movement. |
| `movement.steps.value` | int | 0/309 | Actual value for this type of movement for the day. |
| `movement.steps.distanceInMeters` | float | 0/309 | The distance traveled for the day as part of this type of movement. Given in meters. |
| `movement.pushes` | object | 0/316 | Contains information about movement (e.g.: steps, wheelchair pushes, floors ascended/descended). |
| `movement.floorsAscended` | object | 0/316 | Contains information about movement (e.g.: steps, wheelchair pushes, floors ascended/descended). |
| `movement.floorsAscended.goal` | int | 0/316 | User's daily goal for this type of movement. |
| `movement.floorsAscended.value` | int | 0/316 | Actual value for this type of movement for the day. |
| `movement.floorsAscended.distanceInMeters` | float | 0/316 | The distance traveled for the day as part of this type of movement. Given in meters. |
| `movement.floorsDescended` | object | 0/316 | Contains information about movement (e.g.: steps, wheelchair pushes, floors ascended/descended). |
| `movement.floorsDescended.value` | int | 0/316 | Actual value for this type of movement for the day. |
| `movement.floorsDescended.distanceInMeters` | float | 0/316 | The distance traveled for the day as part of this type of movement. Given in meters. |
| `calories` | object | 0/316 | Contains information about calories burned and consumed. |
| `calories.burnedResting` | int | 0/316 | Basal metabolic rate (BMR) calories burned for the day. |
| `calories.burnedActive` | int | 0/316 | Active calories burned for the day. |
| `calories.burnedTotal` | int | 0/316 | Total calories burned for the day. |
| `heartRate` | object | 0/316 | Contains information about heart rate. Values are given in beats-per-minute (BPM). |
| `heartRate.minValue` | int | 0/309 | Minimum 2-minute average heart rate for the day, or the absolute minimum if no 2-minute average is available. |
| `heartRate.maxValue` | int | 0/309 | Maximum 2-minute average heart rate for the day, or the absolute maximum if no 2-minute average is available. |
| `heartRate.restingValue` | int | 0/309 | Resting heart rate for the day, or the absolute minimum if no resting value is available. |
| `intensityMinutes` | object | 0/316 | Contains information about intensity minutes. |
| `intensityMinutes.goal` | int | 0/316 | User's weekly intensity minutes goal. |
| `intensityMinutes.moderate` | int | 0/316 | Moderate intensity minutes for the day. |
| `intensityMinutes.vigorous` | int | 0/316 | Vigorous intensity minutes for the day. |
| `stress` | object | 0/316 | Contains information about stress. Garmin stress levels are given on a scale of 0 to 100. |
| `stress.avgLevel` | int | 0/316 | Average Garmin stress level. |
| `stress.maxLevel` | int | 0/309 | Maximum Garmin stress level. |
| `stress.restProportion` | float | 0/316 | Proportion of time spent in rest. |
| `stress.activityProportion` | float | 0/316 | Proportion of time spent in activity stress. |
| `stress.uncategorizedProportion` | float | 0/316 | Proportion of time spent in uncategorized stress. |
| `stress.lowStressProportion` | float | 0/316 | Proportion of time spent in low stress. |
| `stress.mediumStressProportion` | float | 0/316 | Proportion of time spent in medium stress. |
| `stress.highStressProportion` | float | 0/316 | Proportion of time spent in high stress. |
| `stress.qualifier` | str | 0/316 | Stress qualifier/descriptor for the day. |
| `stress.totalDurationInMillis` | int | 0/316 | Total duration of stress data. |
| `stress.restDurationInMillis` | int | 0/309 | Duration of rest. |
| `stress.activityDurationInMillis` | int | 0/309 | Duration of activity stress. |
| `stress.uncategorizedDurationInMillis` | int | 0/316 | Duration of uncategorized stress. |
| `stress.lowStressDurationInMillis` | int | 0/309 | Duration of low stress. |
| `stress.mediumStressDurationInMillis` | int | 0/309 | Duration of medium stress. |
| `stress.highStressDurationInMillis` | int | 0/309 | Duration of high stress. |
| `bodyBattery` | object | 0/316 | Contains information about body battery. All values are given on a scale 5 to 100. The term "endOfDay" in this object refers to the last val |
| `bodyBattery.minValue` | int | 0/309 | Minimum Body Battery value for the day. |
| `bodyBattery.maxValue` | int | 0/309 | Maximum Body Battery value for the day. |
| `bodyBattery.chargedValue` | int | 0/309 | Increase in Body Battery over the day. |
| `bodyBattery.drainedValue` | int | 0/309 | Decrease in Body Battery over the day. |
| `bodyBattery.latestValue` | int | 0/309 | Latest Body Battery value. |
| `bodyBattery.featureVersion` | str | 0/310 | The Body Battery feature version for the user's device. |
| `bodyBattery.dynamicFeedbackEvent` | object | 0/315 |  |
| `bodyBattery.dynamicFeedbackEvent.eventTimestampGmt` | str | 0/315 |  |
| `bodyBattery.dynamicFeedbackEvent.bodyBatteryLevel` | str | 0/315 | Body Battery level (0-100). |
| `bodyBattery.dynamicFeedbackEvent.feedbackShortType` | str | 0/315 |  |
| `bodyBattery.dynamicFeedbackEvent.feedbackLongType` | str | 0/315 |  |
| `bodyBattery.endOfDayDynamicFeedbackEvent` | object | 0/307 |  |
| `bodyBattery.endOfDayDynamicFeedbackEvent.eventTimestampGmt` | str | 0/307 |  |
| `bodyBattery.endOfDayDynamicFeedbackEvent.bodyBatteryLevel` | str | 0/307 | Body Battery level (0-100). |
| `bodyBattery.endOfDayDynamicFeedbackEvent.feedbackShortType` | str | 0/307 |  |
| `bodyBattery.endOfDayDynamicFeedbackEvent.feedbackLongType` | str | 0/307 |  |
| `bodyBattery.activityEvents` | array | 0/299 |  |
| `bodyBattery.activityEvents[]` | object | 0/595 |  |
| `bodyBattery.activityEvents[].eventType` | str | 0/595 |  |
| `bodyBattery.activityEvents[].eventStartTimeGmt` | str | 0/595 |  |
| `bodyBattery.activityEvents[].eventStartTimeLocal` | str | 0/595 |  |
| `bodyBattery.activityEvents[].bodyBatteryImpact` | int | 0/595 |  |
| `bodyBattery.activityEvents[].feedbackType` | str | 0/595 |  |
| `bodyBattery.activityEvents[].shortFeedback` | str | 0/595 |  |
| `bodyBattery.activityEvents[].deviceId` | int | 0/595 |  |
| `bodyBattery.activityEvents[].durationInMillis` | int | 0/595 |  |
| `hydration` | object | 0/316 | Contains information about hydration. All values are given in milliliters (ml). |
| `hydration.goalInMl` | int | 0/174 | User's daily hydration goal (this can change day-to-day). |
| `hydration.goalInFractionalMl` | float | 0/174 | User's daily hydration goal (this can change day-to-day). Due to unique client requirements for converting and displaying hydration goals an |
| `hydration.consumedInMl` | int | 0/174 |  |
| `hydration.consumedInFractionalMl` | float | 0/174 | Actual water consumed for the day. Due to unique client requirements for converting and displaying hydration goals and values, this value is |
| `respiration` | object | 0/316 | Contains information about respiration. All values are given in breaths-per-minute. |
| `respiration.avgValue` | int | 0/309 | Average respiration rate for the day. |
| `respiration.maxValue` | int | 0/309 | Maximum respiration rate for the day. |
| `respiration.minValue` | int | 0/309 | Minimum respiration rate for the day. |
| `respiration.latestValue` | int | 0/309 | Latest respiration rate for the day. |
| `respiration.latestTimestampGmt` | str | 0/309 | UTC/GMT timestamp of the latest reading (no local equivalent is available). |
| `respiration.algorithmVersion` | int | 0/309 | Respiration algorithm used. |
| `pulseOx` | object | 0/316 | Contains information about pulse oximetry. All values are given as percentages 0 to 100. |
| `pulseOx.avgAltitudeInMeters` | float | 0/316 | Average monitoring environment altitude for the day. Given as meters above mean sea level. |
| `jetLag` | object | 0/316 |  |
| `calories.consumedRemaining` | int | 0/25 | Remaining calories the user should consume to reach their goal for the day, adjusted upwards for any burned active calories (e.g.: `consumed |
| `heartRate.alertCount` | int | 0/24 | Number of abnormal heart rate alerts for the day. |

## 12 Daily heart rate

`GET /wellness-service/wellness/dailyHeartRate?date`  | sample: 1 record(s) from 1 response(s)  | schema: `DailyHeartRateDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `userProfilePK` | int | 0/1 |  |
| `calendarDate` | str | 0/1 |  |
| `startTimestampGMT` | str | 0/1 |  |
| `endTimestampGMT` | str | 0/1 |  |
| `startTimestampLocal` | str | 0/1 |  |
| `endTimestampLocal` | str | 0/1 |  |
| `maxHeartRate` | int | 0/1 |  |
| `minHeartRate` | int | 0/1 |  |
| `restingHeartRate` | int | 0/1 |  |
| `lastSevenDaysAvgRestingHeartRate` | int | 0/1 |  |
| `heartRateValueDescriptors` | array | 0/1 |  |
| `heartRateValueDescriptors[]` | object | 0/2 |  |
| `heartRateValueDescriptors[].index` | int | 0/2 |  |
| `heartRateValueDescriptors[].key` | str | 0/2 | Name of the column described: timestamp or heartrate. |
| `heartRateValues` | array | 0/1 |  |
| `heartRateValues[]` | array | 0/720 |  |
| `heartRateValues[][]` | int | 0/1440 |  |

Swagger-only top-level fields never seen live (UNVERIFIED): `abnormalHrThresholdValue`, `lowAbnormalHrThresholdValue`, `abnormalHRValuesArray`

## 13 HRV (day)

`GET /hrv-service/hrv/{date}`  | sample: 1 record(s) from 1 response(s)  | schema: `HrvChartDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `userProfilePk` | int | 0/1 |  |
| `hrvSummary` | object | 0/1 | Overnight HRV summary data |
| `hrvSummary.calendarDate` | str | 0/1 | simple date in the YYYY-MM-DD format |
| `hrvSummary.weeklyAvg` | int | 0/1 | average HRV value for the last 7 days |
| `hrvSummary.lastNightAvg` | int | 0/1 | average overnight HRV value |
| `hrvSummary.lastNight5MinHigh` | int | 0/1 | highest overnight average HRV value over a 5-minute period |
| `hrvSummary.baseline` | object | 0/1 | HRV baseline information |
| `hrvSummary.baseline.lowUpper` | int | 0/1 | Upper bound of the 'LOW' HRV range |
| `hrvSummary.baseline.balancedLow` | int | 0/1 | Lower bound of the 'BALANCED' HRV range |
| `hrvSummary.baseline.balancedUpper` | int | 0/1 | Upper bound of the 'BALANCED' HRV range |
| `hrvSummary.baseline.markerValue` | float | 0/1 | Marker value used to mark where the current HRV weekly average falls within the applicable status range |
| `hrvSummary.status` | str | 0/1 | HRV status |
| `hrvSummary.feedbackPhrase` | str | 0/1 | enum |
| `hrvSummary.createTimeStamp` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `hrvReadings` | array | 0/1 | The list of individual HRV readings |
| `hrvReadings[]` | object | 0/101 |  |
| `hrvReadings[].hrvValue` | int | 0/101 |  |
| `hrvReadings[].readingTimeGMT` | str | 0/101 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `hrvReadings[].readingTimeLocal` | str | 0/101 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `startTimestampGMT` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `endTimestampGMT` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `startTimestampLocal` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `endTimestampLocal` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `sleepStartTimestampGMT` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `sleepEndTimestampGMT` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `sleepStartTimestampLocal` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `sleepEndTimestampLocal` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |

## 14 HRV summaries

`GET /hrv-service/hrv/daily/{start}/{end}`  | sample: 265 record(s) from 2 response(s)  | schema: `HrvSummaryDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `calendarDate` | str | 0/265 | simple date in the YYYY-MM-DD format |
| `weeklyAvg` | int | 7/265 | average HRV value for the last 7 days |
| `lastNightAvg` | int | 5/265 | average overnight HRV value |
| `lastNight5MinHigh` | int | 4/265 | highest overnight average HRV value over a 5-minute period |
| `baseline` | object | 18/265 | HRV baseline information |
| `baseline.lowUpper` | int | 0/247 | Upper bound of the 'LOW' HRV range |
| `baseline.balancedLow` | int | 0/247 | Lower bound of the 'BALANCED' HRV range |
| `baseline.balancedUpper` | int | 0/247 | Upper bound of the 'BALANCED' HRV range |
| `baseline.markerValue` | float | 4/247 | Marker value used to mark where the current HRV weekly average falls within the applicable status range |
| `status` | str | 0/265 | HRV status |
| `feedbackPhrase` | str | 0/265 | enum |
| `createTimeStamp` | str | 0/265 | iso 8601 timestamp without GMT offset or 'Z' indicator! |

## 15 Heart rate zones

`GET /biometric-service/heartRateZones/v2`  | sample: 2 record(s) from 1 response(s)  | schema: `HeartRateZonesDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `trainingMethod` | str | 0/2 | Training method used to calculate the heart rate zones. |
| `restingHeartRateUsed` | int | 0/2 | Resting heart rate used to calculate the zones. |
| `lactateThresholdHeartRateUsed` | int | 0/2 | Lactate threshold heart rate used to calculate the zones. |
| `zone1Floor` | int | 0/2 | Lower boundary of heart rate zone 1. |
| `zone2Floor` | int | 0/2 | Lower boundary of heart rate zone 2. |
| `zone3Floor` | int | 0/2 | Lower boundary of heart rate zone 3. |
| `zone4Floor` | int | 0/2 | Lower boundary of heart rate zone 4. |
| `zone5Floor` | int | 0/2 | Lower boundary of heart rate zone 5. |
| `maxHeartRateUsed` | int | 0/2 | Maximum heart rate used to calculate the zones. |
| `restingHrAutoUpdateUsed` | bool | 0/2 | Indicates whether the resting heart rate is automatically updated. |
| `sport` | str | 0/2 | Sport for which the heart rate zones are configured. |

## 18 Fitness age

`GET /fitnessage-service/fitnessage/v2/{date}`  | sample: 1 record(s) from 1 response(s)  | schema: `FitnessAgeDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `chronologicalAge` | int | 0/1 | The user's actual age. |
| `fitnessAge` | float | 0/1 | The user's calculated Fitness Age. |
| `previousFitnessAge` | float | 0/1 | Previous Fitness Age for the user. |
| `components` | object | 0/1 | Details on the components that contributed to the user's Fitness Age. Components include Vigorous Days, Vigorous Minutes, Resting Heart Rate |
| `components.vigorousDaysAvg` | object | 0/1 |  |
| `components.vigorousDaysAvg.value` | float | 0/1 |  |
| `components.vigorousDaysAvg.stale` | bool | 0/1 |  |
| `components.vigorousDaysAvg.numOfWeeksForIm` | int | 0/1 |  |
| `components.rhr` | object | 0/1 |  |
| `components.rhr.value` | int | 0/1 |  |
| `components.rhr.stale` | bool | 0/1 |  |
| `components.vigorousMinutesAvg` | object | 0/1 |  |
| `components.vigorousMinutesAvg.value` | float | 0/1 |  |
| `components.vigorousMinutesAvg.stale` | bool | 0/1 |  |
| `components.vigorousMinutesAvg.numOfWeeksForIm` | int | 0/1 |  |
| `components.bmi` | object | 0/1 |  |
| `components.bmi.value` | float | 0/1 |  |
| `components.bmi.targetValue` | float | 0/1 |  |
| `components.bmi.improvementValue` | float | 0/1 |  |
| `components.bmi.potentialAge` | float | 0/1 |  |
| `components.bmi.priority` | int | 0/1 |  |
| `components.bmi.stale` | bool | 0/1 |  |
| `components.bmi.lastMeasurementDate` | str | 0/1 |  |
| `lastUpdated` | str | 0/1 | The timestamp when the Fitness Age information was last updated. |

Swagger-only top-level fields never seen live (UNVERIFIED): `achievableFitnessAge`, `invalidReason`

## 19 SpO2

`GET /wellness-service/wellness/daily/spo2acclimation/{date}`  | sample: 1 record(s) from 1 response(s)  | schema: `DailySpO2AndAcclimationDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `userProfilePK` | int | 0/1 |  |
| `calendarDate` | str | 0/1 |  |
| `startTimestampGMT` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `endTimestampGMT` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `startTimestampLocal` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `endTimestampLocal` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `sleepStartTimestampGMT` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `sleepEndTimestampGMT` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `sleepStartTimestampLocal` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `sleepEndTimestampLocal` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `tomorrowSleepStartTimestampGMT` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `tomorrowSleepEndTimestampGMT` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `tomorrowSleepStartTimestampLocal` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `tomorrowSleepEndTimestampLocal` | str | 0/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! |
| `averageSpO2` | null only | 1/1 | Average blood oxygen saturation (SpO2) percentage measured during the reporting window. |
| `lowestSpO2` | null only | 1/1 | Lowest blood oxygen saturation (SpO2) percentage measured during the reporting window. |
| `lastSevenDaysAvgSpO2` | null only | 1/1 | Average SpO2 percentage over the trailing 7 days, used as a baseline for acclimation comparison. |
| `latestSpO2` | null only | 1/1 | Most recent SpO2 reading percentage. |
| `latestSpO2TimestampGMT` | null only | 1/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! Timestamp, in UTC, of the most recent SpO2 reading. |
| `latestSpO2TimestampLocal` | null only | 1/1 | iso 8601 timestamp without GMT offset or 'Z' indicator! Timestamp, in the user's local timezone, of the most recent SpO2 reading. |
| `avgSleepSpO2` | null only | 1/1 |  |
| `avgTomorrowSleepSpO2` | null only | 1/1 |  |
| `spO2SingleValuesDescriptorList` | null only | 1/1 |  |
| `spO2SingleValues` | null only | 1/1 | Raw individual SpO2 readings for the day; each inner array is a row described by spO2SingleValuesDescriptorList. |
| `spO2HourlyAveragesDescriptorList` | array | 0/1 | Column descriptors (index + key) describing the layout of each row in spO2HourlyAverages (timestamp, spo2Level, monitoringEnvironmentLevel). |
| `spO2HourlyAveragesDescriptorList[]` | object | 0/3 |  |
| `spO2HourlyAveragesDescriptorList[].spo2ValueDescIndex` | int | 0/3 |  |
| `spO2HourlyAveragesDescriptorList[].spo2ValueDescKey` | str | 0/3 | Name of the column described: timestamp, spo2Level, or monitoringEnvironmentLevel. |
| `spO2HourlyAverages` | array | 0/1 | Hourly-averaged SpO2 acclimation readings; each inner array is a row described by spO2HourlyAveragesDescriptorList. |
| `spO2HourlyAverages[]` | array | 0/24 |  |
| `spO2HourlyAverages[][]` | float, int | 24/72 |  |
| `monitoringEnvironmentValuesDescriptorList` | array | 0/1 | Column descriptors (index + key) describing the layout of each row in monitoringEnvironmentValues (timestamp, monitoringEnvironmentLevel). |
| `monitoringEnvironmentValuesDescriptorList[]` | object | 0/2 |  |
| `monitoringEnvironmentValuesDescriptorList[].monEnvValueDescIndex` | int | 0/2 |  |
| `monitoringEnvironmentValuesDescriptorList[].monEnvValueDescKey` | str | 0/2 | Name of the column described: timestamp or monitoringEnvironmentLevel. |
| `monitoringEnvironmentValues` | array | 0/1 | Raw monitoring-environment level readings; each inner array is a row described by monitoringEnvironmentValuesDescriptorList. |
| `monitoringEnvironmentValues[]` | array | 0/240 |  |
| `monitoringEnvironmentValues[][]` | float, int | 0/480 |  |

## 21 Activity search

`GET /activitylist-service/activities/search/activities`  | sample: 448 record(s) from 5 response(s)  | schema: `SummarizedActivityDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `activityId` | int | 0/448 | Unique identifier of the activity |
| `activityName` | str | 0/448 | Name of the activity |
| `startTimeLocal` | str | 0/448 | Activity start date and time in the local time zone, in yyyy-MM-dd HH:mm:ss format without a UTC offset |
| `startTimeGMT` | str | 0/448 | Activity start date and time in GMT (UTC), in yyyy-MM-dd HH:mm:ss format without a UTC offset suffix |
| `activityType` | object | 0/448 | Activity type for the activity, such as running, cycling, or swimming |
| `activityType.typeId` | int | 0/448 |  |
| `activityType.typeKey` | str | 0/448 |  |
| `activityType.parentTypeId` | int | 0/448 |  |
| `eventType` | object | 0/448 | Event classification associated with the activity |
| `eventType.typeId` | int | 0/448 |  |
| `eventType.typeKey` | str | 0/448 |  |
| `isFavorite` | bool | 0/448 | Indicates whether the user marked the activity as a favorite. |
| `isPR` | bool | 0/448 | Indicates whether the activity is a personal record. |
| `isAutoCalcCalories` | bool | 0/448 | Indicates whether calories were automatically calculated for the activity. |
| `isParent` | bool | 0/448 | Indicates whether the activity is a parent activity. |
| `distance` | float | 0/448 | The distance for the activity, in meters |
| `duration` | float | 0/448 | The duration for the activity, in seconds |
| `elapsedDuration` | float | 0/448 | The elapsed duration for the activity, in seconds |
| `movingDuration` | float | 0/448 | The moving duration for the activity, in seconds |
| `elevationGain` | float | 0/445 | The elevation gain for the activity, in meters |
| `elevationLoss` | float | 0/445 | The elevation loss for the activity, in meters |
| `averageSpeed` | float | 0/448 | The average speed for the activity, in meters per second |
| `maxSpeed` | float | 0/448 | The maximum speed for the activity, in meters per second |
| `startLatitude` | float | 0/446 | The start latitude for the activity, in decimal degrees |
| `startLongitude` | float | 0/446 | The start longitude for the activity, in decimal degrees |
| `hasImages` | bool | 0/448 | Indicates whether the activity has images. |
| `ownerId` | int | 0/448 | Identifier of the activity owner |
| `ownerDisplayName` | str | 0/448 | The owner display name associated with the activity |
| `ownerFullName` | str | 0/448 | The owner full name associated with the activity |
| `ownerProfileImageUrlSmall` | str | 0/448 | The owner profile image url small associated with the activity |
| `ownerProfileImageUrlMedium` | str | 0/448 | The owner profile image url medium associated with the activity |
| `ownerProfileImageUrlLarge` | str | 0/448 | The owner profile image url large associated with the activity |
| `calories` | float | 0/448 | The calories for the activity, in kilocalories |
| `bmrCalories` | float | 0/448 | The BMR calories for the activity, in kilocalories |
| `averageHR` | float | 0/448 | The average heart rate for the activity, in beats per minute |
| `maxHR` | float | 0/448 | The maximum heart rate for the activity, in beats per minute |
| `averageRunningCadenceInStepsPerMinute` | float | 0/435 | The average running cadence for the activity, in steps per minute |
| `maxRunningCadenceInStepsPerMinute` | float | 0/435 | The maximum running cadence for the activity, in steps per minute |
| `steps` | int | 0/435 | Total number of steps recorded for the activity |
| `privacy` | object | 0/448 | Additional AccessControlRule details for the activity |
| `privacy.typeId` | int | 0/448 |  |
| `privacy.typeKey` | str | 0/448 |  |
| `hasVideo` | bool | 0/448 | Indicates whether the activity has a video. |
| `beginTimestamp` | int | 0/448 | Activity start timestamp, in milliseconds since the Unix epoch (1970-01-01T00:00:00Z) |
| `sportTypeId` | int | 0/448 | Identifier of the sport type |
| `aerobicTrainingEffect` | float | 0/448 | The aerobic training effect for the activity |
| `anaerobicTrainingEffect` | float | 0/448 | The anaerobic training effect for the activity |
| `avgStrideLength` | float | 0/435 | Average stride length for the activity, in centimeters |
| `minElevation` | float | 0/445 | The minimum elevation for the activity, in meters |
| `maxElevation` | float | 0/445 | The maximum elevation for the activity, in meters |
| `avgElevation` | float | 0/193 | The average elevation of the activity in meters |
| `maxDoubleCadence` | float | 0/435 | The maximum double cadence for the activity, in steps per minute |
| `isElevationCorrected` | bool | 0/448 | Indicates whether elevation data for the activity has been corrected. |
| `isDecoDive` | bool | 0/448 | Indicates whether the dive was a decompression dive. |
| `summarizedDiveInfo` | object | 0/448 | Additional SummarizedDiveInfo details for the activity |
| `summarizedDiveInfo.summarizedDiveGases` | array | 0/448 |  |
| `maxVerticalSpeed` | float | 0/445 | The maximum vertical speed for the activity, in meters per second |
| `manufacturer` | str | 0/448 | The manufacturer associated with the activity |
| `locationName` | str | 0/446 | Location name associated with the activity |
| `lapCount` | int | 0/448 | Number of laps recorded for the activity |
| `endLatitude` | float | 0/446 | The end latitude for the activity, in decimal degrees |
| `endLongitude` | float | 0/446 | The end longitude for the activity, in decimal degrees |
| `waterEstimated` | float | 0/446 | The water estimated for the activity, in milliliters |
| `trainingEffectLabel` | str | 0/448 | Training-effect label for the activity |
| `activityTrainingLoad` | float | 0/448 | The activity training load for the activity |
| `minActivityLapDuration` | float | 0/448 | The minimum activity lap duration for the activity, in seconds |
| `aerobicTrainingEffectMessage` | str | 0/448 | Aerobic training-effect classification for the activity |
| `anaerobicTrainingEffectMessage` | str | 0/448 | Anaerobic training-effect classification for the activity |
| `splitSummaries` | array | 0/448 | Summary measurements for the activity splits |
| `isManualActivity` | bool | 0/448 | Indicates whether the activity was recorded manually. |
| `moderateIntensityMinutes` | int | 0/441 | The moderate intensity duration for the activity, in minutes |
| `vigorousIntensityMinutes` | int | 0/441 | The vigorous intensity duration for the activity, in minutes |
| `differenceBodyBattery` | int | 0/444 | The difference body battery for the activity |
| `hrTimeInZone_1` | float | 0/448 | The number of seconds that the user's heart rate was in zone 1 |
| `hrTimeInZone_2` | float | 0/448 | The number of seconds that the user's heart rate was in zone 2 |
| `hrTimeInZone_3` | float | 0/448 | The number of seconds that the user's heart rate was in zone 3 |
| `hrTimeInZone_4` | float | 0/448 | The number of seconds that the user's heart rate was in zone 4 |
| `hrTimeInZone_5` | float | 0/448 | The number of seconds that the user's heart rate was in zone 5 |
| `endTimeGMT` | str | 0/448 | Activity end date and time in GMT (UTC), in yyyy-MM-dd HH:mm:ss format without a UTC offset suffix |
| `qualifyingDive` | bool | 0/448 | Indicates whether the dive meets the qualifying criteria. |
| `activityUUID` | str | 0/448 | UUID of the activity |
| `pr` | bool | 0/448 | Indicates whether the activity is a personal record. |
| `autoCalcCalories` | bool | 0/448 | Indicates whether calories were automatically calculated for the activity. |
| `favorite` | bool | 0/448 | Indicates whether the user marked the activity as a favorite. |
| `elevationCorrected` | bool | 0/448 | Indicates whether elevation data for the activity has been corrected. |
| `manualActivity` | bool | 0/448 | Indicates whether the activity was recorded manually. |
| `decoDive` | bool | 0/448 | Indicates whether the activity was a decompression dive. |
| `parent` | bool | 0/448 | Indicates whether the activity is a parent activity. |
| `avgPower` | float | 0/411 | The average power for the activity, in watts |
| `maxPower` | float | 0/411 | The maximum power for the activity, in watts |
| `normPower` | float | 0/411 | The norm power for the activity, in watts |
| `avgVerticalOscillation` | float | 0/411 | The average vertical oscillation for the activity, in centimeters |
| `avgGroundContactTime` | float | 0/411 | The average ground contact time for the activity, in milliseconds |
| `avgVerticalRatio` | float | 0/411 | The average vertical ratio for the activity |
| `splitSummaries[]` | object | 0/1382 |  |
| `splitSummaries[].noOfSplits` | int | 0/1382 | Number of splits represented by this summary |
| `splitSummaries[].totalAscent` | float | 0/1378 | The total ascent for the split, in meters |
| `splitSummaries[].duration` | float | 0/1382 | The duration for the split, in seconds |
| `splitSummaries[].splitType` | str | 0/1382 | Identifies the activity feature represented by the split's data, such as an interval, climb, or dive. |
| `splitSummaries[].maxElevationGain` | float | 0/1378 | The maximum elevation gain for the split, in meters |
| `splitSummaries[].averageElevationGain` | float | 0/1378 | The average elevation gain for the split, in meters |
| `splitSummaries[].maxDistance` | int | 0/1378 | The maximum distance for the split, in meters |
| `splitSummaries[].maxDistanceWithPrecision` | float | 0/301 | Same as maxDistance, but without precision loss, in meters |
| `splitSummaries[].distance` | float | 0/1378 | The distance for the split, in meters |
| `splitSummaries[].averageSpeed` | float | 0/1378 | The average speed for the split, in meters per second |
| `splitSummaries[].maxSpeed` | float | 0/1378 | The maximum speed for the split, in meters per second |
| `splitSummaries[].elevationLoss` | float | 0/1378 | The elevation loss for the split, in meters |
| `splitSummaries[].avgStepFrequency` | float | 0/170 | Average step frequency during the split, in steps per minute |
| `splitSummaries[].avgStepLength` | float | 0/160 | Average step length for the split, in meters |
| `splitSummaries[].verticalRatio` | float | 0/160 | The vertical ratio for the split |
| `splitSummaries[].verticalOscillation` | float | 0/161 | The vertical oscillation for the split, in centimeters |
| `splitSummaries[].avgGroundContactTime` | float | 0/151 | The average ground contact time for the split, in milliseconds |
| `avgGradeAdjustedSpeed` | float | 0/411 | The average grade adjusted speed for the activity, in meters per second |
| `fastestSplit_1000` | float | 0/410 | the time, in seconds, for the fastest 1000 meter split in the activity |
| `fastestSplit_1609` | float | 0/410 | the time, in seconds, for the fastest mile split in the activity |
| `fastestSplit_5000` | float | 0/377 | the time, in seconds, for the fastest 5k meter split in the activity |
| `fastestSplit_10000` | float | 0/201 | the time, in seconds, for the fastest 10k meter split in the activity |
| `fastestSplit_21098` | float | 0/32 | the time, in seconds, for the fastest half marathon split in the activity |
| `powerTimeInZone_1` | float | 0/411 | The number of seconds that the user's power was in zone 1 |
| `powerTimeInZone_2` | float | 0/411 | The number of seconds that the user's power was in zone 2 |
| `powerTimeInZone_3` | float | 0/411 | The number of seconds that the user's power was in zone 3 |
| `powerTimeInZone_4` | float | 0/411 | The number of seconds that the user's power was in zone 4 |
| `powerTimeInZone_5` | float | 0/411 | The number of seconds that the user's power was in zone 5 |
| `fastestSplit_42195` | float | 0/3 | the time, in seconds, for the fastest marathon split in the activity |
| `splitSummaries[].numClimbSends` | int | 0/1226 | Number of climbing routes completed in the split |
| `splitSummaries[].numFalls` | int | 0/1226 | Number of falls recorded in the split |
| `strokes` | float | 0/3 | Total number of strokes recorded for the activity |
| `avgStrokeDistance` | float | 0/3 | The average stroke distance for the activity, in centimeters |
| `maxStrokeCadence` | float | 0/1 | The maximum stroke cadence for the activity, in strokes per minute |
| `averageSwimCadenceInStrokesPerMinute` | float | 0/2 | The average swim cadence in strokes per minute for the activity, in strokes per minute |
| `averageSwolf` | float | 0/2 | The average SWOLF for the activity |
| `activeLengths` | int | 0/2 | Number of active lengths recorded for the activity |
| `poolLength` | float | 0/2 | Pool length, in centimeters. The current implementation returns the normalized value without conversion, even when unitOfPoolLength indicate |
| `unitOfPoolLength` | object | 0/2 | Unit used for the pool length value |
| `unitOfPoolLength.unitKey` | str | 0/2 |  |
| `avgStrokes` | float | 0/2 | Average number of strokes per active length for the activity |
| `fastestSplit_40000` | float | 0/1 | the time, in seconds, for the fastest 40k split in the activity |
| `minRespirationRate` | float | 0/1 | The minimum respiration rate for the activity, in breaths per minute |
| `maxRespirationRate` | float | 0/1 | The maximum respiration rate for the activity, in breaths per minute |
| `avgRespirationRate` | float | 0/1 | The average respiration rate for the activity, in breaths per minute |

Swagger-only top-level fields never seen live (UNVERIFIED): `description`, `comments`, `parentId`, `hasPolyline`, `maxLapAvgRunCadence`, `averageBikingCadenceInRevPerMinute`, `maxBikingCadenceInRevPerMinute`, `maxSwimCadenceInStrokesPerMinute`, `numberOfActivityLikes`, `numberOfActivityComments`, `likedByUser`, `commentedByUser`, `activityLikeDisplayNames`, `activityLikeFullNames`, `activityLikeProfileImageUrls`, `requestorRelationship`, `videoUrl`, `leftBalance`, `rightBalance`, `avgLeftBalance`, `max20MinPower`, `avgFractionalCadence`, `maxFractionalCadence`, `trainingStressScore`, `intensityFactor`, `vO2MaxValue`, `avgGroundContactBalance`, `lactateThresholdBpm`, `getlactateThresholdSpeed`, `maxFtp`, `avgStrokeCadence`, `minStrokes`, `minTemperature`, `maxTemperature`, `avgDoubleCadence`, `summarizedExerciseSets`, `maxDepth`, `avgDepth`, `surfaceInterval`, `startN2`, `endN2`, `startCns`, `endCns`, `diveCount`, `activityLikeAuthors`, `avgVerticalSpeed`, `floorsClimbed`, `floorsDescended`, `diveNumber`, `bottomTime`, `flow`, `grit`, `jumpCount`, `caloriesEstimated`, `caloriesConsumed`, `waterConsumed`, `maxAvgPower_1`, `maxAvgPower_2`, `maxAvgPower_5`, `maxAvgPower_10`, `maxAvgPower_20`, `maxAvgPower_30`, `maxAvgPower_60`, `maxAvgPower_120`, `maxAvgPower_300`, `maxAvgPower_600`, `maxAvgPower_1200`, `maxAvgPower_1800`, `maxAvgPower_3600`, `maxAvgPower_7200`, `maxAvgPower_18000`, `excludeFromPowerCurveReports`, `totalSets`, `activeSets`, `totalReps`, `avgFlow`, `avgGrit`, `avgStress`, `startStress`, `endStress`, `differenceStress`, `maxStress`, `maxBottomTime`, `hasSeedFirstbeatProfile`, `avgWheelchairCadence`, `maxWheelchairCadence`, `avgJumpRopeCadence`, `maxJumpRopeCadence`, `gameName`, `gameType`, `beginPackWeight`, `fastestSplit_100`, `fastestSplit_400`, `fastestSplit_750`, `fastestSplit_1500`, `fastestSplit_91`, `fastestSplit_457`, `fastestSplit_914`, `fastestSplit_1508`, `impactLoad`, `avgStepSpeedLoss`, `avgStepSpeedLossPercent`, `powerTimeInZone_6`, `powerTimeInZone_7`, `longestPoolApneaInterval`, `avgForce`, `maxForce`, `normalizedForce`, `poolApneaBreathHoldDuration`, `deviceDisplayUnitsDTO`, `multiDayActivityUUIDs`, `carbonOffset`, `packWeightCalories`, `standingTime`, `seatedTime`, `maxStandingPower`, `maxSeatedPower`, `avgStandingPower`, `avgSeatedPower`

## 22 Activity detail

`GET /activity-service/activity/{id}`  | sample: 3 record(s) from 3 response(s)  | schema: `ActivitySummaryDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `activityId` | int | 0/3 | Unique identifier of the activity |
| `activityUUID` | object | 0/3 | UUID of the activity |
| `activityUUID.uuid` | str | 0/3 |  |
| `activityName` | str | 0/3 | Name of the activity |
| `userProfileId` | int | 0/3 | Identifier of the user who owns the activity |
| `isMultiSportParent` | bool | 0/3 | Indicates whether this activity is the parent of a multisport activity. |
| `activityTypeDTO` | object | 0/3 | Activity type for the activity, such as running, cycling, or swimming |
| `activityTypeDTO.typeId` | int | 0/3 |  |
| `activityTypeDTO.typeKey` | str | 0/3 |  |
| `activityTypeDTO.parentTypeId` | int | 0/3 |  |
| `eventTypeDTO` | object | 0/3 | Event classification associated with the activity |
| `eventTypeDTO.typeId` | int | 0/3 |  |
| `eventTypeDTO.typeKey` | str | 0/3 |  |
| `accessControlRuleDTO` | object | 0/3 | Privacy settings for the activity |
| `accessControlRuleDTO.typeId` | int | 0/3 |  |
| `accessControlRuleDTO.typeKey` | str | 0/3 |  |
| `timeZoneUnitDTO` | object | 0/3 | Time zone information used for the activity |
| `timeZoneUnitDTO.timeZone` | str | 0/3 |  |
| `metadataDTO` | object | 0/3 | Activity metadata, including availability flags and related identifiers |
| `metadataDTO.isOriginal` | bool | 0/3 |  |
| `metadataDTO.fileFormat` | object | 0/3 |  |
| `metadataDTO.fileFormat.formatId` | int | 0/3 |  |
| `metadataDTO.fileFormat.formatKey` | str | 0/3 |  |
| `metadataDTO.lastUpdateDate` | str | 0/3 |  |
| `metadataDTO.uploadedDate` | str | 0/3 |  |
| `metadataDTO.videoUrl` | null only | 3/3 |  |
| `metadataDTO.userInfoDto` | object | 0/3 |  |
| `metadataDTO.userInfoDto.userProfilePk` | int | 0/3 |  |
| `metadataDTO.userInfoDto.displayname` | str | 0/3 |  |
| `metadataDTO.userInfoDto.fullname` | str | 0/3 |  |
| `metadataDTO.userInfoDto.profileImageUrlLarge` | str | 0/3 |  |
| `metadataDTO.userInfoDto.profileImageUrlMedium` | str | 0/3 |  |
| `metadataDTO.userInfoDto.profileImageUrlSmall` | str | 0/3 |  |
| `metadataDTO.userInfoDto.userPro` | bool | 0/3 |  |
| `metadataDTO.childIds` | array | 0/3 |  |
| `metadataDTO.childActivityTypes` | array | 0/3 |  |
| `metadataDTO.sensors` | null only | 3/3 |  |
| `metadataDTO.activityImages` | array | 0/3 |  |
| `metadataDTO.manufacturer` | str | 0/3 |  |
| `metadataDTO.lapCount` | int | 0/3 |  |
| `metadataDTO.hasRunPowerWindData` | bool | 0/3 |  |
| `metadataDTO.calendarEventInfo` | null only | 3/3 |  |
| `metadataDTO.hasMultiDayActivities` | bool | 0/3 |  |
| `metadataDTO.autoCalcCalories` | bool | 0/3 |  |
| `metadataDTO.favorite` | bool | 0/3 |  |
| `metadataDTO.trimmed` | bool | 0/3 |  |
| `metadataDTO.elevationCorrected` | bool | 0/3 |  |
| `metadataDTO.runPowerWindDataEnabled` | bool | 0/3 |  |
| `metadataDTO.manualActivity` | bool | 0/3 |  |
| `metadataDTO.personalRecord` | bool | 0/3 |  |
| `metadataDTO.gcj02` | bool | 0/3 |  |
| `summaryDTO` | object | 0/3 | Summary measurements and statistics for the activity |
| `summaryDTO.startTimeLocal` | str | 0/3 |  |
| `summaryDTO.startTimeGMT` | str | 0/3 |  |
| `summaryDTO.startLatitude` | float | 0/3 |  |
| `summaryDTO.startLongitude` | float | 0/3 |  |
| `summaryDTO.distance` | float | 0/3 |  |
| `summaryDTO.duration` | float | 0/3 |  |
| `summaryDTO.movingDuration` | float | 0/3 |  |
| `summaryDTO.elapsedDuration` | float | 0/3 |  |
| `summaryDTO.elevationGain` | float | 0/3 |  |
| `summaryDTO.elevationLoss` | float | 0/3 |  |
| `summaryDTO.maxElevation` | float | 0/3 |  |
| `summaryDTO.minElevation` | float | 0/3 |  |
| `summaryDTO.avgElevation` | float | 0/3 |  |
| `summaryDTO.averageSpeed` | float | 0/3 |  |
| `summaryDTO.averageMovingSpeed` | float | 0/3 |  |
| `summaryDTO.maxSpeed` | float | 0/3 |  |
| `summaryDTO.calories` | float | 0/3 |  |
| `summaryDTO.bmrCalories` | float | 0/3 |  |
| `summaryDTO.averageHR` | float | 0/3 |  |
| `summaryDTO.maxHR` | float | 0/3 |  |
| `summaryDTO.minHR` | float | 0/3 |  |
| `summaryDTO.averageRunCadence` | float | 0/3 |  |
| `summaryDTO.maxRunCadence` | float | 0/3 |  |
| `summaryDTO.averagePower` | float | 0/3 |  |
| `summaryDTO.maxPower` | float | 0/3 |  |
| `summaryDTO.minPower` | float | 0/3 |  |
| `summaryDTO.normalizedPower` | float | 0/3 |  |
| `summaryDTO.totalWork` | float | 0/3 |  |
| `summaryDTO.groundContactTime` | float | 0/3 |  |
| `summaryDTO.strideLength` | float | 0/3 |  |
| `summaryDTO.verticalOscillation` | float | 0/3 |  |
| `summaryDTO.trainingEffect` | float | 0/3 |  |
| `summaryDTO.anaerobicTrainingEffect` | float | 0/3 |  |
| `summaryDTO.aerobicTrainingEffectMessage` | str | 0/3 |  |
| `summaryDTO.anaerobicTrainingEffectMessage` | str | 0/3 |  |
| `summaryDTO.verticalRatio` | float | 0/3 |  |
| `summaryDTO.endLatitude` | float | 0/3 |  |
| `summaryDTO.endLongitude` | float | 0/3 |  |
| `summaryDTO.maxVerticalSpeed` | float | 0/3 |  |
| `summaryDTO.waterEstimated` | float | 0/3 |  |
| `summaryDTO.trainingEffectLabel` | str | 0/3 |  |
| `summaryDTO.activityTrainingLoad` | float | 0/3 |  |
| `summaryDTO.minActivityLapDuration` | float | 0/3 |  |
| `summaryDTO.directWorkoutFeel` | int | 0/3 |  |
| `summaryDTO.directWorkoutRpe` | int | 0/3 |  |
| `summaryDTO.directWorkoutComplianceScore` | int | 0/3 |  |
| `summaryDTO.moderateIntensityMinutes` | int | 0/3 |  |
| `summaryDTO.vigorousIntensityMinutes` | int | 0/3 |  |
| `summaryDTO.steps` | int | 0/3 |  |
| `summaryDTO.recoveryHeartRate` | int | 0/1 |  |
| `summaryDTO.avgGradeAdjustedSpeed` | float | 0/3 |  |
| `summaryDTO.differenceBodyBattery` | int | 0/3 |  |
| `locationName` | str | 0/3 | Location name associated with the activity |
| `splitSummaries` | array | 0/3 | Summary measurements for the activity splits |
| `splitSummaries[]` | object | 0/6 |  |
| `splitSummaries[].distance` | float | 0/6 | The distance for the split, in meters |
| `splitSummaries[].duration` | float | 0/6 | The duration for the split, in seconds |
| `splitSummaries[].movingDuration` | float | 0/6 | The moving duration for the split, in seconds |
| `splitSummaries[].elevationGain` | float | 0/6 | The elevation gain for the split, in meters |
| `splitSummaries[].elevationLoss` | float | 0/6 | The elevation loss for the split, in meters |
| `splitSummaries[].averageSpeed` | float | 0/6 | The average speed for the split, in meters per second |
| `splitSummaries[].averageMovingSpeed` | float | 0/6 | The average moving speed for the split, in meters per second |
| `splitSummaries[].maxSpeed` | float | 0/6 | The maximum speed for the split, in meters per second |
| `splitSummaries[].calories` | float | 0/6 | The calories for the split, in kilocalories |
| `splitSummaries[].bmrCalories` | float | 0/6 | The BMR calories for the split, in kilocalories |
| `splitSummaries[].averageHR` | float | 0/6 | The average heart rate for the split, in beats per minute |
| `splitSummaries[].maxHR` | float | 0/6 | The maximum heart rate for the split, in beats per minute |
| `splitSummaries[].averageRunCadence` | float | 0/6 | The average run cadence for the split, in steps per minute |
| `splitSummaries[].maxRunCadence` | float | 0/6 | The maximum run cadence for the split, in steps per minute |
| `splitSummaries[].averagePower` | float | 0/6 | The average power for the split, in watts |
| `splitSummaries[].maxPower` | float | 0/6 | The maximum power for the split, in watts |
| `splitSummaries[].normalizedPower` | float | 0/6 | The normalized power for the split, in watts |
| `splitSummaries[].groundContactTime` | float | 0/6 | The ground contact time for the split, in milliseconds |
| `splitSummaries[].strideLength` | float | 0/6 | Stride length for the split, in centimeters |
| `splitSummaries[].verticalOscillation` | float | 0/6 | The vertical oscillation for the split, in centimeters |
| `splitSummaries[].verticalRatio` | float | 0/6 | The vertical ratio for the split |
| `splitSummaries[].totalExerciseReps` | int | 0/6 | Total number of exercise repetitions recorded for the split |
| `splitSummaries[].avgVerticalSpeed` | float | 0/6 | The average vertical speed for the split, in meters per second |
| `splitSummaries[].avgGradeAdjustedSpeed` | float | 0/6 | The average grade adjusted speed for the split, in meters per second |
| `splitSummaries[].splitType` | str | 0/6 | Identifies the activity feature represented by the split's data, such as an interval, climb, or dive. |
| `splitSummaries[].noOfSplits` | int | 0/6 | Number of splits represented by this summary |
| `splitSummaries[].maxElevationGain` | float | 0/6 | The maximum elevation gain for the split, in meters |
| `splitSummaries[].averageElevationGain` | float | 0/6 | The average elevation gain for the split, in meters |
| `splitSummaries[].maxDistance` | int | 0/6 | The maximum distance for the split, in meters |
| `splitSummaries[].maxDistanceWithPrecision` | float | 0/6 | Same as maxDistance, but without precision loss, in meters |
| `splitSummaries[].avgStepFrequency` | float | 0/6 | Average step frequency during the split, in steps per minute |
| `splitSummaries[].avgStepLength` | float | 0/6 | Average step length for the split, in meters |

Swagger-only top-level fields never seen live (UNVERIFIED): `description`, `parentId`, `parentName`, `diveInfo`, `videoGameInfo`, `deviceDisplayUnitsDTO`

## 23 Activity workouts

`GET /activity-service/activity/{id}/workouts`  | sample: 3 record(s) from 3 response(s)  | schema: `ActivityWorkoutDTO`

| Path | Live type | null seen | Swagger description |
|---|---|---|---|
| `index` | int | 0/3 |  |
| `workoutName` | str | 0/3 | Name of the workout performed during the activity. |
| `timeCreated` | str | 0/3 | ISO 8601 timestamp in UTC, represented without a GMT offset or trailing 'Z' |
| `sport` | str | 0/3 | Sport the workout is intended for, such as RUNNING, CYCLING, or SWIMMING. |
| `manufacturer` | str | 0/3 | Manufacturer associated with the workout, such as GARMIN. |
| `multisportSet` | null only | 3/3 | If the value is in the closed range 0-99, then a multisport transition reset should be performed in between the legs (record a multisport ac |
| `steps` | array | 0/3 |  |
| `steps[]` | object | 0/3 | An individual step or repeat instruction in the activity's structured workout. Describes when the step ends, its training target, and option |
| `steps[].stepIndex` | int | 0/3 | Zero-based index identifying the step within the workout. Repeat instructions use these indices to identify the step to return to. |
| `steps[].name` | null only | 3/3 | Name of the workout step, when supplied. May be null or blank. |
| `steps[].intensity` | str | 0/3 | Role or effort classification of the step, such as ACTIVE, WARMUP, COOLDOWN, REST, or RECOVERY. |
| `steps[].durationType` | str | 0/3 | Condition that ends the step, such as TIME, DISTANCE, REPS, HR_LESS_THAN, HR_GREATER_THAN, OPEN for no fixed duration, and REPEAT_UNTIL_STEP |
| `steps[].durationValue` | float | 0/3 | Value for the step's end condition: seconds for TIME, meters for DISTANCE, or repetition count for REPS. For repeat instructions, this is th |
| `steps[].targetType` | str | 0/3 | Training target for the step, returned as an uppercase identifier such as HEART_RATE, SPEED, CADENCE, POWER, or SWIM_STROKE. OPEN indicates  |
| `steps[].targetValue` | float | 0/3 | Target selector whose meaning depends on the step type. For speed, heart-rate, cadence, or power zones, 0 selects a custom range defined by  |
| `steps[].targetValueLow` | float | 0/3 | Lower bound of a custom target range. Speed is in meters per second. Heart rate uses percentage of maximum heart rate or bpm + 100 (250 mean |
| `steps[].targetValueHigh` | float | 0/3 | Upper bound of a custom target range, using the same units and encoding as targetValueLow. For a heart-rate target, 270 represents 170 bpm. |
| `steps[].notes` | str | 0/3 | Free-text instructions or notes associated with the step. |
| `steps[].equipmentType` | null only | 3/3 | Equipment associated with the step, returned as an uppercase identifier such as NONE, SWIM_FINS, SWIM_KICKBOARD, or SWIM_PULL_BUOY. |
| `steps[].exerciseCategory` | null only | 3/3 | Exercise category associated with the step, returned as an uppercase identifier such as BENCH_PRESS. Used with exerciseName to identify a sp |
| `steps[].exerciseName` | null only | 3/3 | Specific exercise within exerciseCategory, returned as an uppercase identifier rather than localized display text. |
| `steps[].exerciseWeightValue` | null only | 3/3 | Exercise weight, in kilograms, when available. The numeric value remains in kilograms regardless of weightDisplayUnit. |
| `steps[].weightDisplayUnit` | null only | 3/3 | Preferred unit for displaying exerciseWeightValue, returned as an uppercase identifier such as KILOGRAM or POUND. Does not change the unit o |
| `workoutPhrase` | str | 2/3 | Identifier for the adaptive coaching message associated with the workout, describing its training focus or recommendation context. |

## Units and semantics (observed or documented)

Labels: **DOCUMENTED** = stated in the Swagger document. **OBSERVED** = inferred from live values on this account (one person, one device family, one window). **UNVERIFIED** = not proven.

### Time and date semantics

| Topic | Finding | Label |
|---|---|---|
| Sleep `calendarDate` | The date the sleep belongs to is the wake-up date. Example: a sleep that ended on the morning of 2026-10-24 has `calendarDate` 2026-10-24. | OBSERVED |
| Sleep timestamps | `sleepStartTimestampGMT` and `sleepEndTimestampGMT` are epoch milliseconds in UTC. The `...Local` fields are epoch milliseconds shifted by the local UTC offset (a difference of exactly 2 hours was seen in October, summer time). They are not real UTC instants. | DOCUMENTED ("in ms", "user's local timezone offset") + OBSERVED |
| Daily summary (`dailysummaries`) day boundary | `wellnessChronology` runs from local midnight to local midnight (start 22:00 UTC the day before, `totalDurationInMillis` = 86 400 000 in summer time). `calendarDate` is the local calendar day. | OBSERVED |
| Heart-rate day (`dailyHeartRate`) | Window starts at local midnight (22:00 UTC the day before in summer time). Samples are `[epoch ms UTC, bpm]`. | OBSERVED |
| Body Battery day | Window starts at local midnight. Samples are `[epoch ms UTC, level]`. | OBSERVED |
| HRV timestamps | ISO 8601 strings **without** zone designator (`...T21:50:46.0`). The `GMT` variant is UTC, the `Local` variant is local time. | DOCUMENTED ("without GMT offset or Z") |
| HRV `calendarDate` | The morning date of the night measured (the night ending on that date). | OBSERVED |
| Activity times | Search: `startTimeLocal` and `startTimeGMT` are `yyyy-MM-dd HH:mm:ss` strings (no zone), `beginTimestamp` is epoch ms UTC. Detail: ISO strings with `.0` fraction. Detail also carries `timeZoneUnitDTO.timeZone` (an IANA name; the account's activities report `Europe/Paris`, not `Europe/Brussels`: same offsets, different name). | OBSERVED |
| Activity search date filter | `startDate` and `endDate` are inclusive and use the activity's **local** start time. | DOCUMENTED |

### Units

| Field family | Unit | Label |
|---|---|---|
| Sleep `*Seconds`, `sleepNeed.baseline/actual` | Seconds for `*Seconds` fields (stage seconds sum to `sleepTimeSeconds`). `sleepNeed.baseline` and `actual` are minutes (480 = 8 h; the value matches the sleep score's 28 800 s optimum). | OBSERVED (seconds), sleepNeed unit UNVERIFIED |
| Sleep respiration, `avgHeartRate`, `avgSleepStress` | Breaths per minute, bpm, Garmin stress index. Units not stated. | UNVERIFIED |
| Stress daily (`stats/stress/daily`) | `*Duration` fields in seconds (four durations summed to about 71 000 s, less than a day). `overallStressLevel` is an integer index, range not stated (values 19 to 28 seen). | OBSERVED (seconds), range UNVERIFIED |
| Stress in `dailysummaries` | `*DurationInMillis` in milliseconds. `*Proportion` fields are fractions 0 to 1 (0.48 = 48 %). `avgLevel`/`maxLevel` are an integer index. `qualifier` is a string (for example `balanced`). | OBSERVED |
| Steps | `totalSteps` count. `totalDistance` metres (**DOCUMENTED**). | DOCUMENTED |
| Heart rate | bpm. Day series: one sample per 120 s (719 gaps of 120 s in 720 samples; no null samples that day). `restingHeartRate` is one integer per day; the document does not say how it is measured. | OBSERVED, method UNVERIFIED |
| HRV | `lastNightAvg`, `weeklyAvg`, `lastNight5MinHigh`, `hrvValue` are integers in milliseconds by convention. The Swagger text does not state the unit or the measure (RMSSD is **not** named for HRV). Readings are one per 5 minutes during the sleep window (101 readings seen). `baseline.*` are integer bounds, `markerValue` a float. `status` is an enum string (`BALANCED`, `UNBALANCED` seen). | Unit and measure UNVERIFIED. Cadence OBSERVED |
| Body Battery | Level 0 to 100 (**DOCUMENTED** scale). Default response has 6 points per day. Real cadence is one point per 180 s (observed with `chunks=480`). `chunks=N` returns `2N` points per day (lowest and highest value per interval, **DOCUMENTED**). Points with `null` level exist at `chunks` values above the real cadence. | DOCUMENTED + OBSERVED |
| Daily summaries | `calories.*` kcal (UNVERIFIED unit), `movement.steps.distanceInMeters` metres, `intensityMinutes.*` minutes, `respiration.*` breaths per minute (UNVERIFIED), `bodyBattery.*` 0 to 100. | Mixed. Only the names carry the unit |
| Activity (search and detail) | `distance` metres, `duration`/`movingDuration`/`elapsedDuration` seconds, `averageSpeed`/`maxSpeed` metres per second (distance / duration matched `averageSpeed` to 3 digits), `averageHR`/`maxHR` bpm, `elevationGain` metres, `avgStrideLength` centimetres (about 160 seen), `averageRunCadence` steps per minute (both feet, about 170 seen), `groundContactTime` milliseconds (about 237), `verticalOscillation` centimetres (about 10), `verticalRatio` percent, power in watts. `lactateThresholdSpeed` is metres per second in `SummaryDTO` but "centimeters per millisecond" in `SummarizedActivityDTO`: the Swagger document disagrees with itself. | Distance, duration, speed OBSERVED. Rest UNVERIFIED unless the name carries the unit |
| `activityTrainingLoad`, `trainingEffect`, `aerobicTrainingEffect` | Garmin proprietary numbers. Training effect 0 to 5. Load has no stated unit or formula. Present on every activity in the account's history. | UNVERIFIED semantics |
| HR zones | `zone1Floor` to `zone5Floor`, `restingHeartRateUsed`, `maxHeartRateUsed`, `lactateThresholdHeartRateUsed` in bpm. Two records seen: `sport` `DEFAULT` and `RUNNING`, `trainingMethod` `LACTATE_THRESHOLD`. | OBSERVED |
| Fitness age | `fitnessAge` float years, `chronologicalAge` integer. Components can be `stale` with `staleInputs`. | OBSERVED |
| SpO2 | All point-in-time fields were `null` for the sampled day. Only `spO2HourlyAverages` (rows `[epoch ms, spo2 or null, monitoringEnvironmentLevel]`) and `monitoringEnvironmentValues` were populated, and the SpO2 values inside were `null`. The device does not appear to record SpO2 (UNVERIFIED reason). | OBSERVED |

### Cadence summary

| Endpoint | Granularity |
|---|---|
| Stress daily, steps daily, sleep, daily summaries, HRV summaries, fitness age | one record per calendar day |
| Heart rate day | 120 s samples |
| HRV day | 300 s readings, overnight only |
| Body Battery | 180 s underlying cadence; default response has 6 points per day |
| Activity FIT file | 1 s `record` samples (see the capability matrix) |

### Empty-day and gap shapes

| Endpoint | Day with no data |
|---|---|
| `dailysummaries` | HTTP 200 `{"data":[]}`. Days without data are **omitted** from a range (309 entries for 365 days). |
| `stats/stress/daily` | HTTP 200 `[]` |
| `hrv/{date}` | HTTP 204, empty body. Also HTTP 204 for a future date. |
| `hrv/daily/{start}/{end}` | HTTP 200 with entries; entries with no night have `lastNightAvg` null or missing (253 of 258 had a value). HTTP 204 when the whole range is before the data starts. |
| `sleep/dailySleep` | HTTP 200, object with **every field `null`** (see `sleep_empty.json`). |
| `sleepSummariesByDate` | Array with one entry per date. Empty days: UNVERIFIED (not sampled with a range over empty days). |
| `dailyHeartRate` | HTTP 200, object with `calendarDate` set and all other fields `null`. |
| `bodyBattery/reports/daily` | HTTP 200, one object per date with `null` summary fields and placeholder points whose timestamps count up by 1 ms and levels `null`. |
| `fitnessage/v2` | HTTP 200, object with `stale: true` components and no values (see `fitness_age_stale.json`). |
| `wellnessactivity-service/.../summary/range` | HTTP 200 `[]` for every range tried (7 days to 1 year). A non-empty shape was **never seen**: UNVERIFIED. |
| `activities/search` with no match | HTTP 200 `[]` |

### Differences between Swagger and live responses

- `fitnessage/v2/{date}`: Swagger says array of `FitnessAgeDTO`. The live response is a single object.
- `hrv/daily`: Swagger names `HrvDailySummaryDTO` (object with `hrvSummaries`). Matches the live shape.
- Several live `dailysummaries` objects use camelCase `UserDailySummaryV2DTO` fields that are described only by type, not by unit.
