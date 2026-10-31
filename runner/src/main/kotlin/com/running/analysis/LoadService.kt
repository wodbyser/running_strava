package com.running.analysis

import com.running.config.GarminProperties
import com.running.garmin.domain.GarminSleepDay
import com.running.garmin.matching.GarminActivityMatcher
import com.running.garmin.recovery.BodyBatteryAnalysis
import com.running.garmin.recovery.DeviationBand
import com.running.garmin.recovery.RecoveryReport
import com.running.garmin.recovery.RecoveryWindows
import com.running.garmin.recovery.SleepAnalysis
import com.running.garmin.recovery.Trend
import com.running.garmin.spi.GarminRepository
import com.running.load.*
import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.HrZones
import com.running.strava.analysis.WeeklyVolume
import com.running.strava.analysis.isRun
import com.running.strava.analysis.performance.CleanRun
import com.running.strava.analysis.performance.GradeAdjusted
import com.running.strava.analysis.performance.HrZoneSet
import com.running.strava.analysis.performance.LongRun
import com.running.strava.analysis.performance.Stratum
import com.running.strava.analysis.performance.ZoneDistribution
import com.running.strava.db.StravaTokenRepositoryImpl
import com.running.strava.domain.Activity
import com.running.strava.domain.Lap
import com.running.strava.domain.SessionClassifier
import com.running.strava.domain.SessionType
import com.running.strava.spi.ActivityRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

data class CrossCheck(val n: Int, val spearman: Double?)

data class Coverage(
    val sessions: Int,
    val runs: Int,
    val otherSports: Int,
    val fromStream: Int,
    val fromAverage: Int,
    val withoutHr: Int,
    val firstHrDate: LocalDate?,
    val crossCheck: CrossCheck,
    val otherSportsIncluded: Boolean,
)

data class ReferenceInfo(val maxHr: Int, val restHr: Int, val source: String)

/** One day of the load chart. `trimp` is null when unknown (before heart rate data), never 0. */
data class DayPoint(
    val date: LocalDate,
    val runTrimp: Double?,
    val otherTrimp: Double?,
    val trimp: Double?,
    val acute: Double?,
    val chronicWeek: Double?,
    val today: Boolean,
    val sessions: List<String>,
)

data class WeekRow(val week: WeekLoad, val progression: ProgressionRow?, val level: OwnLevel)

data class MorningPoint(val date: LocalDate, val withData: Int, val deviating: Int, val strong: Boolean, val sleepMinutes: Double?)

data class SignalView(val set: SignalSet, val input: SignalInput, val baseRate: Map<Int, Int>, val baseRateDays: Int)

data class ReadinessView(
    val date: LocalDate,
    val fellBack: Boolean,
    val types: List<TypeReadiness>,
    val deviating: Int?,
    val anyStrong: Boolean,
    val sleepShortfall: Double?,
    val daysSinceHard: Int?,
    val daysSinceLong: Int?,
    val hardClass: SessionClass?,
    val recoveryHard: RecoveryTime?,
    val recoveryLong: RecoveryTime?,
    val deviatingNames: List<String>,
)

data class LoadOverview(
    val algorithmVersion: String,
    val today: LocalDate,
    val windows: LoadWindows,
    val reference: ReferenceInfo?,
    val coverage: Coverage,
    val days: List<DayPoint>,
    val acute: WindowSum,
    val chronic: WindowSum,
    val differencePct: Double?,
    val acuteBand: OwnBand.Result,
    val todayLoad: DayLoad?,
    val weeks: List<WeekRow>,
    val lastMonotony: WeekLoad?,
    val monotonyBand: OwnBand.Result,
    val timeline: List<MorningPoint>,
    val signals: SignalView?,
    val readiness: ReadinessView?,
    val model: FfResult,
    val forecasts: List<ForecastCheck>,
    val sleepTargetMinutes: Int,
)

data class LoadResponse(
    val algorithmVersion: String,
    val today: LocalDate,
    val windows: LoadWindows,
    val firstGarminDay: LocalDate?,
    val sessionCount: Int,
    val classes: List<ClassResponse>,
    val restReference: List<MetricResponse>,
    val recoveryTimes: List<ClassRecovery>,
    val rest: List<RestGroup>,
    val deload: DeloadReport,
    val longRuns: List<DurationGroup>,
    val fatigue: FatigueAdjustedReport,
    val frequency: FrequencyReport,
    val blocks: BlockReport,
    val lag: AdaptationLagReport,
    val detraining: DetrainingReport,
    val perfPoints: Int,
    val perfStratum: String?,
)

/**
 * Loads activities, streams and Garmin rows, runs the pure load analysis and caches the expensive per-activity part in memory.
 * No schema change. Logs counts and the algorithm version only: no heart rate, load, recovery value or activity name.
 */
@Service
class LoadService(
    private val activityRepository: ActivityRepository,
    private val garminRepository: GarminRepository,
    private val performanceService: PerformanceService,
    private val recoveryService: RecoveryService,
    private val tokenRepository: StravaTokenRepositoryImpl,
    private val properties: GarminProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config: LoadConfig get() = properties.load.toConfig()

    private data class Heavy(val trimp: Trimp.Result, val hardSeconds: Double?, val flatMeters: Double?, val kj: Double?)

    private val cache = ConcurrentHashMap<Triple<Long, String, String>, Heavy>()

    fun today(): LocalDate = LocalDate.now(clock.withZone(ZONE))

    /** Everything both pages share. Not cached as a whole: Garmin rows and windows change between requests. */
    private class Base(
        val today: LocalDate,
        val cfg: LoadConfig,
        val reference: HrReference?,
        val sessions: List<LoadSession>,
        val days: List<DayLoad>,
        val weeks: List<WeekLoad>,
        val firstKnown: LocalDate?,
        val series: RecoverySeries,
        val sleepDays: List<GarminSleepDay>,
        val points: List<PerfPoint>,
        val longRunEf: Map<Long, Double>,
        val perfStratum: String?,
        val coverage: Coverage,
    )

    private fun reference(runs: List<Activity>, today: LocalDate): HrReference? {
        val rows = garminRepository.findHeartRateZones()
        fun usable(r: com.running.garmin.domain.GarminHeartRateZones): Boolean {
            val max = r.maxHr
            val rest = r.restingHr
            return max != null && rest != null && max > rest
        }
        val row = rows.firstOrNull { it.sport.equals("RUNNING", true) && usable(it) }
            ?: rows.firstOrNull { it.sport.equals("DEFAULT", true) && usable(it) }
        if (row != null) return HrReference(row.maxHr!!, row.restingHr!!, "Garmin-instelling (${if (row.sport.equals("RUNNING", true)) "hardlopen" else "standaard"})")
        val max = HrZones.estimateMaxHrForRuns(runs, ZonedDateTime.now(clock))
        val garminRest = LoadStats.median(garminRepository.findRestingHr(today.minusDays(28), today).mapNotNull { it.restingHr?.toDouble() })?.toInt()
        val rest = garminRest ?: tokenRepository.getRestingHr()
        return if (max != null && rest != null && max > rest) HrReference(max, rest, "app-schatting (max-HR uit je runs, rusthartslag ${if (garminRest != null) "uit Garmin" else "handmatig"})") else null
    }

    private fun heavy(a: Activity, ref: HrReference, set: HrZoneSet?, cfg: LoadConfig): Heavy {
        val key = Triple(a.id, "${ref.maxHr}/${ref.restHr}/${set?.floors}", LoadAlgorithm.VERSION + cfg.trimpB + cfg.minHrCoverage)
        cache[key]?.let { return it }
        val stream = activityRepository.findStreams(a.id)
        val trimp = Trimp.compute(stream, a.averageHeartrate?.toDouble(), a.movingTime, ref, cfg)
        var hard: Double? = null
        var flat: Double? = null
        if (a.isRun() && stream != null) {
            val cleaned = CleanRun.clean(stream, a.movingTime.toDouble())
            if (cleaned != null) {
                if (set != null && cleaned.coverage >= cfg.minHrCoverage) {
                    val z = ZoneDistribution.seconds(ZoneDistribution.histogram(cleaned.samples), set)
                    hard = z[3] + z[4]
                }
                if (GradeAdjusted.assess(cleaned.samples, cleaned.hasGrade).available) {
                    val used = cleaned.samples.filter { val g = it.gradePct; g != null && kotlin.math.abs(g) <= GradeAdjusted.IGNORE_ABOVE_PCT }
                    val usedDist = used.sumOf { it.dd }
                    if (usedDist > 0) flat = a.distance.toDouble() * used.sumOf { it.dd * GradeAdjusted.factor(it.gradePct ?: 0.0) } / usedDist
                }
            }
        }
        val watts = a.averageWatts
        val kj = if (a.deviceWatts == true && watts != null) watts.toDouble() * a.movingTime / 1000.0 else null
        return Heavy(trimp, hard, flat, kj).also { cache[key] = it }
    }

    private fun sessionClass(a: Activity, laps: List<Lap>, medianMoving: Double?): SessionClass {
        if (!a.isRun()) return SessionClass.OTHER
        val elevPerKm = if (a.distance > 0) a.totalElevationGain / (a.distance / 1000.0) else 0.0
        return when (SessionClassifier.classify(laps, a.workoutType, elevPerKm.toDouble())) {
            SessionType.RACE -> SessionClass.RACE
            SessionType.INTERVAL -> SessionClass.INTERVAL
            SessionType.TEMPO -> SessionClass.TEMPO
            else -> if (LongRun.isLongRun(a.workoutType, a.movingTime, medianMoving)) SessionClass.LONG else SessionClass.EASY
        }
    }

    private fun base(): Base {
        val started = System.nanoTime()
        val cfg = config
        val today = today()
        val all = activityRepository.findAll().filter { !it.isFlagged && !it.isManual }
        val runs = all.filter { it.isRun() }.sortedBy { it.startDate }
        val used = (if (cfg.includeOtherSports) all else runs).sortedBy { it.startDate }
        val ref = reference(runs, today)
        val laps = activityRepository.findLapsForActivities(runs.map { it.id })
        val set = HrZoneSet.garmin(garminRepository.findHeartRateZones()) ?: HrZones.estimateMaxHrForRuns(runs, ZonedDateTime.now(clock))?.let { HrZoneSet.app(it) }

        val runDates = runs.map { ActivityTime.localDate(it) to it.movingTime.toDouble() }
        val sessions = if (ref == null) emptyList() else used.map { a ->
            val date = ActivityTime.localDate(a)
            val recent = runDates.filter { it.first.isBefore(date) && !it.first.isBefore(date.minusDays(90)) }.map { it.second }
            val median = if (recent.size >= 5) LoadStats.median(recent) else null
            val h = heavy(a, ref, set, cfg)
            LoadSession(
                id = a.id, date = date, name = a.name, sport = a.sportType ?: a.type, isRun = a.isRun(),
                cls = sessionClass(a, laps[a.id].orEmpty(), median), movingSeconds = a.movingTime,
                distanceMeters = a.distance.toDouble(), elevationGain = a.totalElevationGain.toDouble(),
                flatEquivalentMeters = h.flatMeters, hardSeconds = h.hardSeconds, trimp = h.trimp.trimp, trimpSource = h.trimp.source, kilojoules = h.kj,
            )
        }
        val firstKnown = DailyLoad.firstKnown(sessions)
        val firstDay = sessions.minOfOrNull { it.date }
        val days = if (firstDay == null) emptyList() else DailyLoad.build(sessions, firstKnown, WeeklyVolume.weekStart(firstDay), today)
        val weeks = Weekly.build(days, today)

        val series = garminSeries(today)
        val sleepDays = garminRepository.findSleep(GARMIN_FROM, today)
        val perf = performancePoints(sessions)

        val garminActs = garminRepository.findAllActivities()
        val matches = GarminActivityMatcher().match(garminActs, runs).filter { it.stravaActivityId != null }
        val pairs = matches.mapNotNull { m ->
            val app = sessions.firstOrNull { it.id == m.stravaActivityId }?.takeIf { it.trimpSource != TrimpSource.NONE }?.trimp
            val g = garminActs.firstOrNull { it.activityId == m.garminActivityId }?.activityTrainingLoad
            if (app != null && g != null) app to g else null
        }
        val cross = CrossCheck(pairs.size, if (pairs.size >= 8) LoadStats.spearman(pairs.map { it.first }, pairs.map { it.second }) else null)
        val coverage = Coverage(
            sessions.size, sessions.count { it.isRun }, sessions.count { !it.isRun },
            sessions.count { it.trimpSource == TrimpSource.STREAM }, sessions.count { it.trimpSource == TrimpSource.AVERAGE },
            sessions.count { it.trimpSource == TrimpSource.NONE }, firstKnown, cross, cfg.includeOtherSports,
        )
        log.info(
            "Load base {}: sessions={} streamLoad={} averageLoad={} withoutHr={} days={} weeks={} perfPoints={} cache={} ms={}",
            LoadAlgorithm.VERSION, sessions.size, coverage.fromStream, coverage.fromAverage, coverage.withoutHr, days.size, weeks.size,
            perf.first.size, cache.size, (System.nanoTime() - started) / 1_000_000,
        )
        return Base(today, cfg, ref, sessions, days, weeks, firstKnown, series, sleepDays, perf.first, perf.second, perf.third, coverage)
    }

    private fun garminSeries(today: LocalDate): RecoverySeries {
        val hrv = garminRepository.findHrv(GARMIN_FROM, today)
        val sleep = garminRepository.findSleep(GARMIN_FROM, today)
        val rhr = garminRepository.findRestingHr(GARMIN_FROM, today)
        val samples = garminRepository.findBodyBatterySamples(
            GARMIN_FROM.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            today.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        return RecoverySeries(
            hrv = hrv.mapNotNull { r -> r.lastNightAvg?.let { r.calendarDate to it.toDouble() } }.toMap(),
            restingHr = rhr.mapNotNull { r -> r.restingHr?.let { r.calendarDate to it.toDouble() } }.toMap(),
            sleepMinutes = sleep.mapNotNull { r -> r.sleepTimeSeconds?.let { r.calendarDate to it / 60.0 } }.toMap(),
            bodyBatteryWake = BodyBatteryAnalysis.wakeLevels(samples, sleep).mapValues { it.value.toDouble() },
        )
    }

    /** Efficiency of the steady runs as % of the own median, in the zone group with most steady runs. Reuses the Phase 3 per-run cache. */
    private fun performancePoints(sessions: List<LoadSession>): Triple<List<PerfPoint>, Map<Long, Double>, String?> {
        val report = performanceService.report()
        val names = sessions.associate { it.id to it.name }
        val byStratum = report.efPoints.groupBy { it.stratum }
        val best: Stratum = byStratum.maxByOrNull { it.value.size }?.key ?: return Triple(emptyList(), emptyMap(), null)
        val list = byStratum.getValue(best)
        val med = LoadStats.median(list.map { it.ef }) ?: return Triple(emptyList(), emptyMap(), null)
        val points = list.sortedBy { it.date }.map { PerfPoint(it.id, it.date, names[it.id] ?: "", it.ef / med * 100) }
        val longEf = report.longRuns.associate { it.id to it.thirds.efChangePct }
        return Triple(points, longEf, best.text)
    }

    private fun trailing7(daily: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
        val out = LinkedHashMap<LocalDate, Double>()
        var d = from
        while (!d.isAfter(to)) {
            Rolling.window(daily, d, 7).sum?.let { out[d] = it }
            d = d.plusDays(1)
        }
        return out
    }

    // ------------------------------------------------------------------ overview

    fun overview(w: LoadWindows = LoadWindows.DEFAULT): LoadOverview = overview(base(), w)

    private fun overview(b: Base, w: LoadWindows): LoadOverview {
        val started = System.nanoTime()
        val today = b.today
        val yesterday = today.minusDays(1)
        val daily = DailyLoad.trimpMap(b.days.filter { it.date != today })
        val acute = Rolling.window(daily, yesterday, w.acuteDays, b.cfg.minKnownShare)
        val chronic = Rolling.window(daily, yesterday, w.chronicDays, b.cfg.minKnownShare)
        val diff = Rolling.differencePct(acute, chronic)

        val t7 = trailing7(daily, yesterday.minusWeeks(w.bandWeeks.toLong()), yesterday)
        val last7 = Rolling.window(daily, yesterday, 7).sum
        val acuteBand = OwnBand.evaluate(t7.filterKeys { it.isBefore(yesterday) }.values.toList(), last7, MIN_BAND_DAYS)

        val chartFrom = today.minusDays(w.chartDays.toLong() - 1)
        val byDate = b.days.associateBy { it.date }
        val points = generateSequence(chartFrom) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { d ->
            val day = byDate[d]
            val a = if (d == today) null else Rolling.window(daily, d, w.acuteDays, b.cfg.minKnownShare).perWeek
            val c = if (d == today) null else Rolling.window(daily, d, w.chronicDays, b.cfg.minKnownShare).perWeek
            val known = day?.trimp != null
            DayPoint(d, if (known) day?.runTrimp else null, if (known) day?.otherTrimp else null, day?.trimp, a, c, d == today, day?.sessions?.map { it.name }.orEmpty())
        }.toList()

        val progression = Progression.rows(b.weeks, b.cfg).associateBy { it.weekStart }
        val completeKnown = b.weeks.filter { it.complete && it.knownDays == 7 && it.trimp != null }
        val shownFrom = WeeklyVolume.weekStart(today).minusWeeks(maxOf(w.bandWeeks, w.chartDays / 7).toLong())
        val weekRows = b.weeks.filter { !it.weekStart.isBefore(shownFrom) }.map { wk ->
            val hist = completeKnown.filter { it.weekStart.isBefore(wk.weekStart) && !it.weekStart.isBefore(wk.weekStart.minusWeeks(w.bandWeeks.toLong())) }.map { it.trimp!! }
            val level = if (wk.complete && wk.trimp != null && wk.knownDays == 7) OwnBand.evaluate(hist, wk.trimp, MIN_BAND_WEEKS).level else OwnLevel.UNKNOWN
            WeekRow(wk, progression[wk.weekStart], level)
        }
        val lastMon = completeKnown.lastOrNull { it.monotony != null }
        val monoHistory = completeKnown.filter { it.monotony != null && lastMon != null && it.weekStart.isBefore(lastMon.weekStart) && !it.weekStart.isBefore(lastMon.weekStart.minusWeeks(w.bandWeeks.toLong())) }.map { it.monotony!! }
        val monotonyBand = OwnBand.evaluate(monoHistory, lastMon?.monotony, MIN_BAND_WEEKS)

        val timeline = generateSequence(chartFrom) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { d ->
            val dev = DailyDeviation.forDay(b.series, d, w.normDays)
            MorningPoint(d, dev.withData, dev.deviating, dev.strong, b.series.sleepMinutes[d])
        }.toList()

        val recovery = if (b.series.hrv.isEmpty() && b.series.restingHr.isEmpty()) null
        else runCatching { recoveryService.defaultDate().let { (d, fell) -> recoveryService.report(d) to fell } }.getOrNull()

        val model = FitnessFatigue.analyze(daily, b.firstKnown, today, b.points, b.cfg)
        val forecasts = listOf(
            RecoveryForecast.analyze("HRV (Garmin, nachtgemiddelde)", "ms", b.series.hrv, today, b.cfg),
            RecoveryForecast.analyze("Rusthartslag", "bpm", b.series.restingHr, today, b.cfg),
        )

        val (signals, readiness) = signalsAndReadiness(b, w, t7, last7, monoHistory, lastMon, recovery)
        log.info("Load overview {}: days={} weeks={} signalsActive={} ms={}", LoadAlgorithm.VERSION, points.size, weekRows.size, signals?.set?.active, (System.nanoTime() - started) / 1_000_000)
        return LoadOverview(
            LoadAlgorithm.VERSION, today, w, b.reference?.let { ReferenceInfo(it.maxHr, it.restHr, it.source) }, b.coverage, points,
            acute, chronic, diff, acuteBand, byDate[today], weekRows, lastMon, monotonyBand, timeline, signals, readiness, model, forecasts,
            properties.recovery.sleepTargetMinutes,
        )
    }

    private fun signalsAndReadiness(
        b: Base, w: LoadWindows, t7: Map<LocalDate, Double>, last7: Double?,
        monoHistory: List<Double>, lastMon: WeekLoad?, recovery: Pair<RecoveryReport, Boolean>?,
    ): Pair<SignalView?, ReadinessView?> {
        if (recovery == null && b.points.isEmpty() && last7 == null) return null to null
        val today = b.today
        val efPct = RelativeEf.series(b.points)
        fun efMedian(d: LocalDate): Pair<Double?, Int> {
            val v = efPct.filter { it.first.date.isAfter(d.minusDays(w.efSignalDays.toLong())) && !it.first.date.isAfter(d) }.map { it.second }
            return LoadStats.median(v) to v.size
        }
        val load90 = LoadStats.percentile(t7.values.toList(), b.cfg.hardPercentile)
        val mono90 = LoadStats.percentile(monoHistory, b.cfg.hardPercentile)
        val (efMed, efN) = efMedian(today.minusDays(1))
        val report = recovery?.first
        val input = SignalInput(
            report?.hrv?.metric?.trend?.sustained, report?.restingHr?.metric?.trend?.sustained, report?.sleepDebt?.target?.band,
            efMed, efN, last7, load90, lastMon?.monotony, mono90,
        )
        val set = FatigueSignals.evaluate(input)

        // base rate over the last 12 months: counts only, not a probability
        val target = properties.recovery.sleepTargetMinutes
        val rw = RecoveryWindows.DEFAULT
        val hist = (1..365).map { today.minusDays(it.toLong()) }.filter { b.series.hrv.containsKey(it) || b.series.restingHr.containsKey(it) }
        val monoByWeek = b.weeks.filter { it.complete && it.knownDays == 7 && it.monotony != null }
        val counts = hist.map { d ->
            val hrv = Trend.evaluate(b.series.hrv, d, rw).sustained
            val rhr = Trend.evaluate(b.series.restingHr, d, rw).sustained
            val sleepBand = SleepAnalysis.debt(b.sleepDays, d, target, rw).target?.band
            val lp90 = LoadStats.percentile(t7.filterKeys { it.isBefore(d) && !it.isBefore(d.minusWeeks(w.bandWeeks.toLong())) }.values.toList(), b.cfg.hardPercentile)
            val lastWeek = monoByWeek.lastOrNull { it.weekStart.plusDays(6).isBefore(d) }
            val mp90 = lastWeek?.let { lw -> LoadStats.percentile(monoByWeek.filter { it.weekStart.isBefore(lw.weekStart) && !it.weekStart.isBefore(lw.weekStart.minusWeeks(w.bandWeeks.toLong())) }.map { it.monotony!! }, b.cfg.hardPercentile) }
            val (em, en) = efMedian(d)
            FatigueSignals.evaluate(SignalInput(hrv, rhr, sleepBand, em, en, t7[d], lp90, lastWeek?.monotony, mp90)).active
        }
        val view = SignalView(set, input, FatigueSignals.baseRate(counts), counts.size)

        val sleepLast = recovery?.let { b.series.sleepMinutes[it.first.date] }
        val shortfall = sleepLast?.let { maxOf(0.0, target - it) }
        val upToday = b.sessions.filter { !it.date.isAfter(today) }
        val hardIds = HardSessions.ids(upToday, b.cfg)
        val lastHard = upToday.filter { it.id in hardIds }.maxByOrNull { it.date }
        val lastLong = upToday.filter { it.cls == SessionClass.LONG }.maxByOrNull { it.date }
        val times = ResponseAnalyzer(b.series, b.sessions, b.cfg, w).recoveryTimes().associate { it.cls to it.time }
        val summary = report?.readiness?.summary
        fun dutch(k: String) = when (k) { "HRV" -> "HRV"; "RESTING_HR" -> "rusthartslag"; "SLEEP" -> "slaap"; "BODY_BATTERY" -> "Body Battery"; else -> k.lowercase() }
        val strongNames = summary?.deviating?.filter { it.band == DeviationBand.STRONG }?.map { dutch(it.kind.name) }.orEmpty()
        val strong = strongNames.isNotEmpty()
        val readinessInput = ReadinessInput(
            deviatingComponents = summary?.takeIf { it.available }?.deviating?.size, anyStrongDeviation = strong,
            daysSinceHard = lastHard?.let { ChronoUnit.DAYS.between(it.date, today).toInt() },
            recoveryAfterHardClass = lastHard?.let { times[it.cls] },
            daysSinceLong = lastLong?.let { ChronoUnit.DAYS.between(it.date, today).toInt() },
            recoveryAfterLong = times[SessionClass.LONG],
            load7Level = OwnBand.evaluate(t7.filterKeys { it.isBefore(today.minusDays(1)) }.values.toList(), last7, MIN_BAND_DAYS).level,
            sleepShortfallMinutes = shortfall, activeSignals = set.active.takeIf { set.withData >= 3 },
            deviatingNames = summary?.deviating?.map { dutch(it.kind.name) }.orEmpty(), strongNames = strongNames,
            activeSignalNames = set.signals.filter { it.active == true }.map { it.key.label },
        )
        val names = summary?.deviating?.map { it.kind.name } ?: emptyList()
        return view to ReadinessView(
            report?.date ?: today, recovery?.second ?: false, SessionReadiness.evaluate(readinessInput), readinessInput.deviatingComponents, strong,
            shortfall, readinessInput.daysSinceHard, readinessInput.daysSinceLong, lastHard?.cls, readinessInput.recoveryAfterHardClass, readinessInput.recoveryAfterLong, names,
        )
    }

    // ------------------------------------------------------------------ response page

    fun response(w: LoadWindows = LoadWindows.DEFAULT): LoadResponse = response(base(), w)

    private fun response(b: Base, w: LoadWindows): LoadResponse {
        val started = System.nanoTime()
        val today = b.today
        val cfg = b.cfg
        val analyzer = ResponseAnalyzer(b.series, b.sessions, cfg, w)
        val firstGarmin = (b.series.hrv.keys + b.series.restingHr.keys).minOrNull()
        val daily = DailyLoad.trimpMap(b.days.filter { it.date != today })
        val deviating = HashMap<LocalDate, Int?>()
        b.points.forEach { p ->
            val d = DailyDeviation.forDay(b.series, p.date, w.normDays)
            deviating[p.date] = if (d.available) d.deviating else null
        }
        val runInEnd = b.firstKnown?.plusDays(cfg.modelRunInDays.toLong())
        val rest = if (firstGarmin == null) emptyList() else RestDays.analyze(b.sessions, b.series, firstGarmin, today.minusDays(2), cfg)
        val result = LoadResponse(
            LoadAlgorithm.VERSION, today, w, firstGarmin, b.sessions.size,
            analyzer.byClass(),
            if (firstGarmin == null) emptyList() else analyzer.restReference(firstGarmin.plusDays(w.normDays.toLong()), today.minusDays(w.followUpMornings + 1L), w.followUpMornings),
            analyzer.recoveryTimes(), rest,
            Deload.analyze(b.weeks, b.series, b.points, w, cfg),
            LongRunDuration.analyze(analyzer, b.longRunEf, cfg, w.followUpMornings, w.isolationDays),
            FatigueAdjusted.analyze(b.points, daily, deviating, cfg),
            Frequency.analyze(b.weeks, b.series, w, cfg),
            BlockEffect.analyze(BlockEffect.blocks(b.sessions, b.weeks, b.points), cfg),
            AdaptationLag.analyze(b.weeks, b.points, runInEnd, cfg),
            Detraining.analyze(b.days, b.weeks, b.series, b.points, w, cfg),
            b.points.size, b.perfStratum,
        )
        log.info("Load response {}: sessions={} perfPoints={} deloads={} episodes={} ms={}", LoadAlgorithm.VERSION, b.sessions.size, b.points.size, result.deload.weeks.size, result.detraining.episodes.size, (System.nanoTime() - started) / 1_000_000)
        return result
    }

    /**
     * Fills the Phase 3 and Phase 4 per-activity caches so the first page request is fast. Runs once after start-up on its own
     * daemon thread and never fails the app. Logs counts only (through the normal load log lines).
     */
    @EventListener(ApplicationReadyEvent::class)
    fun warmUpInBackground() {
        if (!properties.load.warmUp) return
        Thread({ warmUp() }, "load-warmup").apply { isDaemon = true; start() }
    }

    fun warmUp(): Boolean = try {
        overview(base(), LoadWindows.DEFAULT)
        true
    } catch (e: Exception) {
        log.warn("Load warm-up failed: {}", e.javaClass.simpleName)
        false
    }

    fun both(w: LoadWindows = LoadWindows.DEFAULT): Pair<LoadOverview, LoadResponse> {
        val b = base()
        return overview(b, w) to response(b, w)
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Brussels")
        val GARMIN_FROM: LocalDate = LocalDate.of(2024, 1, 1)
        const val MIN_BAND_DAYS = 28
        const val MIN_BAND_WEEKS = 8
    }
}
