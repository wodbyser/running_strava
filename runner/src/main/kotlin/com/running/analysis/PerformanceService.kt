package com.running.analysis

import com.running.config.GarminProperties
import com.running.garmin.spi.GarminRepository
import com.running.strava.analysis.EfVerdict
import com.running.strava.analysis.HrZones
import com.running.strava.analysis.isRun
import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.WeeklyVolume
import com.running.strava.analysis.performance.*
import com.running.strava.domain.Activity
import com.running.strava.domain.Lap
import com.running.strava.domain.SessionClassifier
import com.running.strava.domain.SessionType
import com.running.strava.spi.ActivityRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/** Everything derived from one run. Small on purpose (no raw samples), so it can be cached per activity. */
data class RunAnalysis(
    val id: Long,
    val date: LocalDate,
    val name: String,
    val distanceMeters: Double,
    val movingSeconds: Int,
    val avgHr: Double?,
    val sessionType: SessionType,
    val isTrainer: Boolean,
    val workoutType: Int?,
    val hasStream: Boolean,
    val coverage: Double,
    val validSeconds: Double,
    val medianIntervalSeconds: Double?,
    val histogram: HrHistogram?,
    val steadyFailure: SteadyFailure?,
    val ef: EfRun?,
    val speedCv: Double?,
    val drift: DriftAnalysis.Result?,
    val durability: DurabilityProfile.Run?,
    val thirds: LongRun.Thirds?,
    val gap: GradeAdjusted.Result?,
    val efforts: List<CriticalSpeed.Effort>,
    val intervals: IntervalPerformance.Session?,
    val tempC: Double?,
    val elevationPerKm: Double,
    val terrain: TerrainClass,
    val watts: Double?,
    val measuredPower: Boolean,
    @get:com.fasterxml.jackson.annotation.JsonIgnore val startLat: Double?,
    @get:com.fasterxml.jackson.annotation.JsonIgnore val startLon: Double?,
) {
    val steady: Boolean get() = steadyFailure == null
}

data class DriftRow(val id: Long, val date: LocalDate, val minutes: Double, val drift: DriftAnalysis.Result, val tempC: Double?, val name: String = "")
data class LongRunRow(val id: Long, val date: LocalDate, val minutes: Double, val thirds: LongRun.Thirds, val name: String = "")
data class DurabilityRow(val id: Long, val date: LocalDate, val run: DurabilityProfile.Run, val stratum: Stratum?, val name: String = "", val minutes: Double = 0.0)
data class IntervalRow(val id: Long, val date: LocalDate, val session: IntervalPerformance.Session, val name: String = "")
/** EF of the steady window of one run, for the scatter chart. */
data class EfPoint(val id: Long, val date: LocalDate, val ef: Double, val stratum: Stratum)
data class RecentRun(val a: RunAnalysis)

data class CsView(
    val outcome: CriticalSpeed.Outcome,
    val hrAtCs: Double?,
    val hrAnchors: Int,
    val validation: CriticalSpeed.Validation,
    val garminLtHr: Int?,
    val garminSport: String?,
)

data class PerformanceReport(
    val algorithmVersion: String,
    val today: LocalDate,
    val runCount: Int,
    val runsWithStream: Int,
    val runsWithHr: Int,
    val steadyCount: Int,
    val failureCounts: Map<String, Int>,
    val zoneSet: HrZoneSet?,
    val zoneNote: String,
    val maxHr: Int?,
    val consistency: Consistency.Result,
    val efTrends: List<EfTrend>,
    /** The two comparison windows (days) used for [efTrends], shortest first. */
    val efWindows: List<Int>,
    /** Every period used on the overview, as chosen by the user (defaults otherwise). */
    val windows: PerformanceWindows,
    val efMonthly: List<EfMonth>,
    val efPoints: List<EfPoint>,
    /** Steady runs (all time) per HR stratum: explains why some strata never have a trend. */
    val steadyPerStratum: Map<Stratum, Int>,
    val driftRows: List<DriftRow>,
    val driftSummary: String,
    val fitness: FitnessView.Summary,
    val cs: CsView,
    val weeks: List<PeriodZones>,
    val months: List<PeriodZones>,
    val mixWeeks: List<SessionMix>,
    val mix4Weeks: List<SessionMix>,
    val durabilityRows: List<DurabilityRow>,
    val durabilitySummary: String,
    val longRuns: List<LongRunRow>,
    val longRunSummary: String,
    val intervals: List<IntervalRow>,
    val signatureGroups: Map<String, List<IntervalRow>>,
    val hrr: List<HeartRateRecovery.Summary>,
    val hrrN: Int,
    val power: PowerEfficiency.Result,
    val recent: List<RecentRun>,
)

/** Your own medians over steady runs of the last [days] days, as context for one run (no norm, no verdict). */
data class TypicalValues(
    val days: Int,
    val n: Int,
    val efSameStratum: Double?,
    val nEf: Int,
    val driftPct: Double?,
    val decouplingPct: Double?,
    val efChangeAt60Pct: Double?,
    val nDurability: Int,
)

data class RunReport(
    val run: RunAnalysis,
    val zoneSet: HrZoneSet?,
    val zoneSeconds: List<Double>?,
    val comparable: ComparableRuns.Result,
    val zoneNote: String,
    val reference: RunProfile? = null,
    val typical: TypicalValues? = null,
    /** Strava names of the comparable runs, by activity id. */
    val names: Map<Long, String> = emptyMap(),
)

/** Loads runs, streams and laps, runs the pure analysis, caches per-run results in memory. Logs counts only. */
@Service
class PerformanceService(
    private val activityRepository: ActivityRepository,
    private val garminRepository: GarminRepository,
    private val properties: GarminProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<Triple<Long, Int?, String>, RunAnalysis>()
    private val config: PerformanceConfig get() = properties.performance.toConfig()

    fun today(): LocalDate = LocalDate.now(clock.withZone(ZONE))

    private fun eligibleRuns(): List<Activity> = activityRepository.findAll().filter { it.isRun() && !it.isManual && !it.isFlagged }

    private fun analyze(a: Activity, laps: List<Lap>, maxHr: Int?): RunAnalysis {
        val key = Triple(a.id, maxHr, PerformanceAlgorithm.VERSION + config.hashCode())
        cache[key]?.let { return it }
        val result = compute(a, laps, maxHr)
        if (result.hasStream) cache[key] = result
        return result
    }

    private fun compute(a: Activity, laps: List<Lap>, maxHr: Int?): RunAnalysis {
        val cfg = config
        val stream = activityRepository.findStreams(a.id)
        val elevationPerKm = TerrainClass.elevationPerKm(a)
        val type = SessionClassifier.classify(laps, a.workoutType, elevationPerKm)
        val cleaned = stream?.let { CleanRun.clean(it, a.movingTime.toDouble()) }
        val date = ActivityTime.localDate(a)
        val steadyRes = SteadyRun.evaluate(a, type, cleaned, maxHr, cfg)
        val failure = when {
            steadyRes.failure == SteadyFailure.NO_STREAM && stream != null -> SteadyFailure.NO_HR
            steadyRes.failure == null && a.isTrainer -> SteadyFailure.SESSION_TYPE
            else -> steadyRes.failure
        }
        val steady = failure == null
        val coverageOk = cleaned != null && cleaned.coverage >= cfg.minHrCoverage && cleaned.validSeconds >= 300
        val elev = TerrainClass.elevationPerKm(a)
        val temp = cleaned?.let { Series.medianTemp(it.samples) }
        val ef = if (steady && cleaned != null) {
            val w = steadyRes.window
            EfRun(a.id, date, Series.distance(w), steadyRes.windowSeconds, steadyRes.avgHr!!, steadyRes.ef!!,
                GradeAdjusted.efGap(w, cleaned.hasGrade), Series.medianTemp(w), elev)
        } else null
        val efforts = if (stream != null && !a.isTrainer) {
            CriticalSpeed.DISTANCES.mapNotNull { CriticalSpeed.effort(a.id, date, a.workoutType == 1, stream, it) }
        } else emptyList()
        return RunAnalysis(
            id = a.id, date = date, name = a.name, distanceMeters = a.distance.toDouble(), movingSeconds = a.movingTime,
            avgHr = a.averageHeartrate?.toDouble(), sessionType = type, isTrainer = a.isTrainer, workoutType = a.workoutType,
            hasStream = stream != null, coverage = cleaned?.coverage ?: 0.0, validSeconds = cleaned?.validSeconds ?: 0.0,
            medianIntervalSeconds = cleaned?.medianIntervalSeconds,
            histogram = if (coverageOk) ZoneDistribution.histogram(cleaned!!.samples) else null,
            steadyFailure = failure, ef = ef, speedCv = steadyRes.speedCv,
            drift = if (steady) DriftAnalysis.analyze(steadyRes.window, cleaned!!.hasGrade) else null,
            durability = if (steady) DurabilityProfile.analyze(cleaned!!.samples, cfg.warmupSeconds, cfg.minDurabilityMinutes * 60.0).takeIf { it.eligible } else null,
            thirds = if (coverageOk && cleaned!!.validSeconds >= LongRun.MIN_MINUTES * 60) LongRun.thirds(cleaned.samples, cfg.warmupSeconds) else null,
            gap = if (cleaned != null && !a.isTrainer && coverageOk) GradeAdjusted.assess(cleaned.samples, cleaned.hasGrade) else null,
            efforts = efforts,
            intervals = if (type == SessionType.INTERVAL) IntervalPerformance.analyze(laps, a.workoutType, stream, maxHr, elevationPerKm) else null,
            tempC = temp, elevationPerKm = elev, terrain = TerrainClass.of(elev),
            watts = a.averageWatts?.toDouble(), measuredPower = PowerEfficiency.hasMeasuredPower(a),
            startLat = a.startLatlng?.getOrNull(0), startLon = a.startLatlng?.getOrNull(1),
        )
    }

    private fun zoneSet(pref: String?, maxHr: Int?): Pair<HrZoneSet?, String> {
        val garmin = HrZoneSet.garmin(garminRepository.findHeartRateZones())
        val app = maxHr?.let { HrZoneSet.app(it) }
        val want = if (pref.equals("app", true)) "app" else if (pref.equals("garmin", true)) "garmin" else properties.performance.zoneSet
        val note = "Huidige zones toegepast op oude runs: de geschiedenis van je zone-instellingen is niet bekend. "
        return when {
            want == "app" && app != null -> app to (note + ZoneSource.APP.text + ".")
            garmin != null && want != "app" -> garmin to (note + ZoneSource.GARMIN.text + ".")
            app != null -> app to (note + ZoneSource.APP.text + (if (want == "garmin") " (geen Garmin-zones beschikbaar)." else "."))
            else -> null to "Geen zones beschikbaar (geen Garmin-zones en geen hartslagdata voor een schatting van de max-HR)."
        }
    }

    fun report(zonePref: String? = null, w: PerformanceWindows = PerformanceWindows.DEFAULT): PerformanceReport {
        val started = System.nanoTime()
        val cfg = config
        val today = today()
        val runs = eligibleRuns().sortedBy { it.startDate }
        val laps = activityRepository.findLapsForActivities(runs.map { it.id })
        val maxHr = HrZones.estimateMaxHrForRuns(runs, ZonedDateTime.now(clock))
        val all = runs.map { analyze(it, laps[it.id].orEmpty(), maxHr) }
        val (set, zoneNote) = zoneSet(zonePref, maxHr)

        fun inLast(d: LocalDate, days: Long) = d.isAfter(today.minusDays(days)) && !d.isAfter(today)
        fun inPrev(d: LocalDate, days: Long) = d.isAfter(today.minusDays(2 * days)) && !d.isAfter(today.minusDays(days))

        val failures = all.filter { it.steadyFailure != null }.groupingBy { it.steadyFailure!!.text }.eachCount()
        val steadyRuns = all.filter { it.steady && it.ef != null }

        val efStrata = if (set != null) steadyRuns.map { it.ef!! to Stratum.of(it.ef.avgHr, set) } else emptyList()
        val windows = listOf(w.efShortDays, w.efLongDays).distinct().sorted()
        val efTrends = if (set == null) emptyList() else Stratum.entries.flatMap { s -> windows.map { EfIndex.trend(efStrata, s, today, it, cfg) } }
        // The fitness view has its own window, independent of the EF section.
        val efTrendsFitness = if (set == null) emptyList() else Stratum.entries.map { EfIndex.trend(efStrata, it, today, w.trendDays, cfg) }
        val efMonthly = EfIndex.monthly(efStrata)
        val efPoints = efStrata.map { (r, s) -> EfPoint(r.activityId, r.date, r.ef, s) }
        val steadyPerStratum = Stratum.entries.associateWith { s -> efStrata.count { it.second == s } }

        val driftAll = steadyRuns.filter { it.drift != null }
        val driftRows = driftAll.filter { inLast(it.date, w.driftDays.toLong()) }
            .map { DriftRow(it.id, it.date, it.ef!!.windowMinutes, it.drift!!, it.tempC, it.name) }.sortedByDescending { it.date }
        val driftOk = driftRows.filter { it.drift.driftAvailable }
        val driftSummary = if (driftOk.size >= cfg.minRunsDrift) {
            "Mediaan over ${driftOk.size} stabiele runs (${w.driftDays} dagen): drift ${"%.1f".format(java.util.Locale.ROOT, Stats.median(driftOk.map { it.drift.driftPct })!!)} %, " +
                "ontkoppeling ${"%.1f".format(java.util.Locale.ROOT, Stats.median(driftRows.map { it.drift.decouplingPct })!!)} %."
        } else "Onvoldoende data voor een samenvatting (n = ${driftOk.size} runs met bruikbare drift, minimum ${cfg.minRunsDrift})."

        // critical speed
        val efforts = all.flatMap { it.efforts }
        val csDays = w.csDays.toLong()
        val anchors = CriticalSpeed.anchors(efforts, today, csDays)
        val outcome = CriticalSpeed.fit(anchors)
        val prevOutcome = CriticalSpeed.fit(CriticalSpeed.anchors(efforts, today.minusDays(w.trendDays.toLong()), csDays))
        val (hrCs, hrN) = (outcome as? CriticalSpeed.Outcome.Ok)?.let { CriticalSpeed.hrAtCs(it.fit) } ?: (null to 0)
        val garminRow = garminRepository.findHeartRateZones().firstOrNull { it.sport.equals("RUNNING", true) && it.lactateThresholdHr != null }
        val cs = CsView(outcome, hrCs, hrN, CriticalSpeed.walkForward(efforts, csDays), garminRow?.lactateThresholdHr, garminRow?.sport)

        // zones
        val zoneRuns = all.filter { it.histogram != null }.map { ZoneRun(it.date, it.histogram!!, it.sessionType, it.distanceMeters) }
        val firstWeek = WeeklyVolume.weekStart(today).minusWeeks(w.zoneWeeks - 1L)
        val weeks = if (set != null) ZoneDistribution.byWeek(zoneRuns.filter { !it.date.isBefore(firstWeek) && !it.date.isAfter(today) }, set) else emptyList()
        val firstMonth = java.time.YearMonth.from(today).minusMonths(w.intensityMonths - 1L).atDay(1)
        val months = if (set != null) ZoneDistribution.byMonth(zoneRuns.filter { !it.date.isBefore(firstMonth) && !it.date.isAfter(today) }, set) else emptyList()
        val mixRuns = all.map { ZoneRun(it.date, emptyMap(), it.sessionType, it.distanceMeters) }
        val mixWeeks = ZoneDistribution.sessionMixByWeek(mixRuns).takeLast(12)
        val mix4 = ZoneDistribution.sessionMixBy4Weeks(mixRuns, today).takeLast((w.mixWeeks + 3) / 4)

        // durability, long runs
        val durRows = steadyRuns.filter { it.durability != null }.map { r ->
            DurabilityRow(r.id, r.date, r.durability!!, set?.let { Stratum.of(r.ef!!.avgHr, it) }, r.name, r.validSeconds / 60)
        }.sortedByDescending { it.date }
        val dur60 = durRows.filter { it.run.efChangeAt60Pct != null }
        val durB = dur60.filter { inLast(it.date, w.durabilityDays.toLong()) }.map { it.run.efChangeAt60Pct!! }
        val durSummary = if (durB.size >= cfg.minRunsDurability) "Mediaan EF-verandering op minuut 60: ${"%.1f".format(java.util.Locale.ROOT, Stats.median(durB)!!)} % (n = ${durB.size}, ${w.durabilityDays} dagen)."
        else "Onvoldoende data voor een samenvatting (n = ${durB.size} runs van minstens ${cfg.minDurabilityMinutes} min, minimum ${cfg.minRunsDurability})."

        // Long run definition: always against the median of the last 90 days, so the chosen window does not change which runs count.
        val recentRuns = all.filter { inLast(it.date, 90) }
        val medMoving = Stats.median(recentRuns.map { it.movingSeconds.toDouble() })
        val longRows = all.filter { it.thirds != null && LongRun.isLongRun(it.workoutType, it.movingSeconds, medMoving) }
            .map { LongRunRow(it.id, it.date, it.validSeconds / 60, it.thirds!!, it.name) }.sortedByDescending { it.date }
        val lrB = longRows.filter { inLast(it.date, w.longRunDays.toLong()) }
        val longSummary = if (lrB.size >= cfg.minLongRunsList) {
            val m = { f: (LongRun.Thirds) -> Double -> "%.1f".format(java.util.Locale.ROOT, Stats.median(lrB.map { f(it.thirds) })!!) }
            "Mediaan over ${lrB.size} lange runs (${w.longRunDays} dagen): tempo ${m { it.speedChangePct }} %, HR ${m { it.hrChangeBpm }} bpm, EF ${m { it.efChangePct }} %." +
                if (lrB.size >= cfg.minLongRunsTrend) "" else " (een trend vraagt ${cfg.minLongRunsTrend} lange runs)"
        } else "Onvoldoende data voor een samenvatting (n = ${lrB.size} lange runs, minimum ${cfg.minLongRunsList})."

        // intervals and HRR
        val intervalRows = all.filter { it.intervals != null }.map { IntervalRow(it.id, it.date, it.intervals!!, it.name) }.sortedByDescending { it.date }
        val groups = intervalRows.filter { it.session.signature != null }.groupBy { it.session.signature!! }
        val hrrAll = intervalRows.filter { inLast(it.date, w.hrrDays.toLong()) }.flatMap { r -> r.session.reps.mapNotNull { it.hrr } }
        val medianInterval = Stats.median(all.mapNotNull { it.medianIntervalSeconds })
        val hrr = if (medianInterval != null && medianInterval > HeartRateRecovery.MAX_SAMPLE_INTERVAL_SECONDS) emptyList()
        else HeartRateRecovery.summarize(hrrAll, cfg.minHrrMeasurements)

        // power
        val steadyDates = steadyRuns.map { it.date }
        val powerRuns = steadyRuns.filter { it.measuredPower && it.watts != null }.map {
            PowerRun(it.id, it.date, it.movingSeconds.toDouble(), it.distanceMeters, it.avgHr ?: 0.0, it.watts!!)
        }.filter { it.avgHr > 0 }
        val power = PowerEfficiency.analyze(steadyDates, powerRuns, today, w.powerDays, cfg)

        // fitness view (#21)
        val fitness = FitnessView.summarize(fitnessIndicators(efTrendsFitness, outcome, prevOutcome, driftAll, dur60, today, cfg, w.trendDays))

        val consistency = Consistency.analyze(all.map { it.date to it.distanceMeters }, today, cfg.minConsistencyWeeks, w.consistencyShortWeeks, w.consistencyLongWeeks)

        val report = PerformanceReport(
            PerformanceAlgorithm.VERSION, today, all.size, all.count { it.hasStream }, all.count { it.histogram != null || (it.hasStream && it.steadyFailure != SteadyFailure.NO_HR) },
            steadyRuns.size, failures, set, zoneNote, maxHr, consistency, efTrends, windows, w, efMonthly, efPoints, steadyPerStratum, driftRows, driftSummary, fitness, cs,
            weeks, months, mixWeeks, mix4, durRows, durSummary, longRows, longSummary, intervalRows, groups, hrr, hrrAll.size, power,
            all.sortedByDescending { it.date }.take(40).map { RecentRun(it) },
        )
        log.info(
            "Performance computed {}: runs={} streams={} steady={} efforts={} intervalSessions={} cache={} ms={}",
            PerformanceAlgorithm.VERSION, all.size, report.runsWithStream, steadyRuns.size, efforts.size, intervalRows.size, cache.size,
            (System.nanoTime() - started) / 1_000_000,
        )
        return report
    }

    private fun fitnessIndicators(
        efTrends: List<EfTrend>,
        now: CriticalSpeed.Outcome,
        prev: CriticalSpeed.Outcome,
        driftAll: List<RunAnalysis>,
        dur60: List<DurabilityRow>,
        today: LocalDate,
        cfg: PerformanceConfig,
        days: Int,
    ): List<FitnessView.Indicator> {
        fun inLast(d: LocalDate) = d.isAfter(today.minusDays(days.toLong())) && !d.isAfter(today)
        fun inPrev(d: LocalDate) = d.isAfter(today.minusDays(2L * days)) && !d.isAfter(today.minusDays(days.toLong()))
        val ef90 = efTrends.filter { it.windowDays == days }.maxByOrNull { it.nA + it.nB }
        val a = if (ef90 != null) FitnessView.Indicator(
            "ef", "Aerobe efficiëntie (stabiele runs)", ef90.direction, FitnessView.Favourable.WHEN_HIGHER,
            "hogere EF = meer afstand per hartslag", ef90.nA, ef90.nB,
            (ef90.changePct?.let { "${"%+.1f".format(java.util.Locale.ROOT, it)} % meter per hartslag; " } ?: "") +
                "${ef90.nB} runs recent, ${ef90.nA} ervoor (${ef90.stratum.text})",
        ) else FitnessView.Indicator("ef", "Aerobe efficiëntie (stabiele runs)", Direction.INSUFFICIENT, FitnessView.Favourable.WHEN_HIGHER, "hogere EF = meer afstand per hartslag", 0, 0, "geen hartslagzones beschikbaar")

        val b = if (now is CriticalSpeed.Outcome.Ok && prev is CriticalSpeed.Outcome.Ok) {
            val change = (now.fit.csMs - prev.fit.csMs) / prev.fit.csMs * 100
            val overlap = now.fit.csRangeMs.first <= prev.fit.csRangeMs.second && prev.fit.csRangeMs.first <= now.fit.csRangeMs.second
            val dir = when {
                kotlin.math.abs(change) <= EfVerdict.THRESHOLD_PCT -> Direction.STABLE
                overlap -> Direction.UNCERTAIN
                change > 0 -> Direction.HIGHER
                else -> Direction.LOWER
            }
            FitnessView.Indicator("cs", "Geschatte kritische snelheid", dir, FitnessView.Favourable.WHEN_HIGHER, "hogere snelheid = sneller bij een inspanning van 3 tot 30 minuten", 1, 1, "${"%+.1f".format(java.util.Locale.ROOT, change)} % tegenover de schatting van $days dagen geleden")
        } else FitnessView.Indicator("cs", "Geschatte kritische snelheid", Direction.INSUFFICIENT, FitnessView.Favourable.WHEN_HIGHER, "hogere snelheid = sneller bij een inspanning van 3 tot 30 minuten", 0, 0, "nog geen twee bruikbare schattingen (nu en $days dagen geleden)")

        fun diff(label: String, key: String, fav: FitnessView.Favourable, meaning: String, unit: String, va: List<Double>, vb: List<Double>, min: Int, thr: Double): FitnessView.Indicator {
            val r = DifferenceTrend.evaluate(va, vb, min, thr)
            return FitnessView.Indicator(key, label, r.direction, fav, meaning, r.nA, r.nB,
                (r.difference?.let { "${"%+.1f".format(java.util.Locale.ROOT, it)} $unit; " } ?: "") + "${r.nB} runs recent, ${r.nA} ervoor (minimum $min per periode)")
        }
        val c = diff("Hartslagdrift (stabiele runs)", "drift", FitnessView.Favourable.WHEN_LOWER, "lagere drift = minder HR-stijging", "procentpunt",
            driftAll.filter { inPrev(it.date) && it.drift!!.driftAvailable }.map { it.drift!!.driftPct },
            driftAll.filter { inLast(it.date) && it.drift!!.driftAvailable }.map { it.drift!!.driftPct }, cfg.minRunsDrift, 1.0)
        val d = diff("EF-verandering op minuut 60", "durability", FitnessView.Favourable.WHEN_HIGHER, "minder EF-verlies na een uur = steviger", "procentpunt",
            dur60.filter { inPrev(it.date) }.map { it.run.efChangeAt60Pct!! }, dur60.filter { inLast(it.date) }.map { it.run.efChangeAt60Pct!! }, cfg.minRunsDurability, EfVerdict.THRESHOLD_PCT)
        return listOf(a, b, c, d)
    }

    fun runReport(id: Long, zonePref: String? = null, typicalDays: Int = DEFAULT_TYPICAL_DAYS): RunReport? {
        val runs = eligibleRuns()
        val target = runs.firstOrNull { it.id == id } ?: activityRepository.findById(id)?.takeIf { it.isRun() } ?: return null
        val laps = activityRepository.findLapsForActivities((runs.map { it.id } + id).distinct())
        val maxHr = HrZones.estimateMaxHrForRuns(runs.ifEmpty { listOf(target) }, ZonedDateTime.now(clock))
        val (set, note) = zoneSet(zonePref, maxHr)
        val analysis = analyze(target, laps[id].orEmpty(), maxHr)
        val zoneSeconds = if (set != null && analysis.histogram != null) ZoneDistribution.seconds(analysis.histogram, set) else null
        val profiles = (runs.filter { it.id != id } + target).distinctBy { it.id }.map { a -> profile(a, analyze(a, laps[a.id].orEmpty(), maxHr), set, !(a.isManual || a.isFlagged)) }
        val ref = profiles.first { it.activityId == id }
        val comparable = ComparableRuns.find(ref, profiles)
        val wanted = comparable.matches.map { it.run.activityId }.toSet()
        val names = runs.filter { it.id in wanted }.associate { it.id to it.name }
        return RunReport(analysis, set, zoneSeconds, comparable, note, ref, typical(runs, laps, maxHr, set, analysis, typicalDays), names)
    }

    private fun typical(runs: List<Activity>, laps: Map<Long, List<Lap>>, maxHr: Int?, set: HrZoneSet?, target: RunAnalysis, days: Int): TypicalValues {
        val today = today()
        val recent = runs.filter { it.id != target.id }.map { analyze(it, laps[it.id].orEmpty(), maxHr) }
            .filter { it.steady && it.ef != null && it.date.isAfter(today.minusDays(days.toLong())) && !it.date.isAfter(today) }
        val stratum = if (set != null && target.ef != null) Stratum.of(target.ef.avgHr, set) else null
        val sameStratum = if (stratum != null) recent.filter { Stratum.of(it.ef!!.avgHr, set!!) == stratum } else emptyList()
        val dur = recent.mapNotNull { it.durability?.efChangeAt60Pct }
        return TypicalValues(
            days = days,
            n = recent.size,
            efSameStratum = Stats.median(sameStratum.map { it.ef!!.ef }), nEf = sameStratum.size,
            driftPct = Stats.median(recent.mapNotNull { r -> r.drift?.takeIf { it.driftAvailable }?.driftPct }),
            decouplingPct = Stats.median(recent.mapNotNull { it.drift?.decouplingPct }),
            efChangeAt60Pct = Stats.median(dur), nDurability = dur.size,
        )
    }

    private fun profile(a: Activity, r: RunAnalysis, set: HrZoneSet?, eligible: Boolean) = RunProfile(
        activityId = a.id, date = r.date, sessionType = r.sessionType, isTrainer = a.isTrainer, distanceMeters = a.distance.toDouble(),
        movingSeconds = a.movingTime, terrain = r.terrain, elevationPerKm = r.elevationPerKm, hasHr = r.avgHr != null, avgHr = r.avgHr,
        zone = if (r.avgHr != null && set != null) set.zoneOf(Math.round(r.avgHr).toInt()) else null, tempC = r.tempC,
        startLat = r.startLat, startLon = r.startLon, eligible = eligible, gapSpeed = r.gap?.gapSpeed, efWindow = r.ef?.ef,
        driftPct = r.drift?.takeIf { it.driftAvailable }?.driftPct,
    )

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Brussels")
        const val DEFAULT_TYPICAL_DAYS = 90
        const val MIN_TYPICAL_DAYS = 14
        const val MAX_TYPICAL_DAYS = 730
        private val ZONE_ID = ZONE
    }
}
