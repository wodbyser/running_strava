package com.running.analysis

import com.running.config.GarminProperties
import com.running.garmin.recovery.DeviationBand
import com.running.garmin.recovery.ReadinessKind
import com.running.garmin.recovery.RecoveryReport
import com.running.garmin.spi.GarminRepository
import com.running.load.DailyLoad
import com.running.load.ReadinessType
import com.running.load.SignalKey
import com.running.load.TypeReadiness
import com.running.race.*
import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.BestEffortFinder
import com.running.strava.analysis.HrZones
import com.running.strava.analysis.WeeklyVolume
import com.running.strava.analysis.isRun
import com.running.strava.analysis.performance.CriticalSpeed
import com.running.strava.analysis.performance.TerrainClass
import com.running.strava.domain.Activity
import com.running.strava.domain.SessionClassifier
import com.running.strava.spi.ActivityRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

enum class RangeBasis(val text: String) {
    OWN_ERRORS("de afwijkingen van je eigen eerdere voorspellingen"),
    ANCHOR_SPREAD("alleen de spreiding van je ankers"),
}

/** One distance: the Riegel prediction, its range, the critical speed time beside it (5 km and shorter) and the anchors behind it. */
data class DistanceForecast(
    val distanceMeters: Double,
    val standard: Boolean,
    val prediction: RacePrediction?,
    val rangeLowSeconds: Double?,
    val rangeHighSeconds: Double?,
    val rangeBasis: RangeBasis,
    val criticalSpeedSeconds: Double?,
)

data class PlanView(
    val targetSeconds: Double,
    val basis: String,
    val distanceMeters: Double,
    val even: List<Split>,
    val calm: List<Split>,
    val calmMeters: Double,
    val calmPct: Double,
)

data class GoalView(
    val goal: RaceGoal,
    val daysToGo: Long,
    val past: Boolean,
    val forecast: DistanceForecast?,
    val feasibility: Feasibility?,
    val matchedRace: RaceRun?,
)

data class PacingRow(val id: Long, val date: LocalDate, val name: String, val distanceMeters: Double, val halves: Halves)

data class PacingView(val summary: Pacing.Summary, val rows: List<PacingRow>)

data class WeekCheckComponent(val kind: String, val label: String, val value: Double?, val median: Double?, val deviation: Double?, val band: String?)

data class WeekCheckDay(val date: LocalDate, val withData: Int, val deviating: Int, val strong: Int, val names: List<String>)

data class RaceWeekView(
    val goal: RaceGoal,
    val daysToGo: Long,
    val recoveryDate: LocalDate?,
    val components: List<WeekCheckComponent>,
    val days: List<WeekCheckDay>,
    val sleepTargetShortfallMinutes: Double?,
    val loadPct: Double?,
    val signalsActive: Int?,
    val signalNames: List<String>,
    val daysSinceHard: Int?,
)

data class RaceOverview(
    val algorithmVersion: String,
    val today: LocalDate,
    val windows: RaceWindows,
    val runCount: Int,
    val raceCount: Int,
    val maxHr: Int?,
    val anchors: List<RaceAnchor>,
    val forecasts: List<DistanceForecast>,
    val paceViolations: List<Pair<Double, Double>>,
    val outcomes: List<RaceOutcome>,
    val accuracy: AccuracySummary,
    val weekly: List<WeekPrediction>,
    val goals: List<GoalView>,
    val focus: GoalView?,
    val plan: PlanView?,
    val pacing: PacingView,
    val fitness: ReadinessBlock,
    val recovery: TypeReadiness?,
    val specificSeconds: Double?,
    val raceWeek: RaceWeekView?,
    val raceWeekHiddenDays: Long?,
    val candidates: List<Candidate>,
)

data class TaperRow(val outcome: RaceOutcome, val taper: TaperResult)

data class RaceHistoryReport(
    val algorithmVersion: String,
    val today: LocalDate,
    val windows: RaceWindows,
    val outcomes: List<RaceOutcome>,
    val accuracy: AccuracySummary,
    val postRaces: List<PostRace>,
    val taper: List<TaperRow>,
    val taperSummary: Taper.Summary,
    val prep: List<PrepRow>,
    val groups: PrepGroups?,
    val pacing: PacingView,
)

/**
 * Loads activities, streams and Garmin rows, runs the pure race analysis and returns the overview. Everything is computed on demand, nothing
 * is stored except the goals. Logs counts and the algorithm version only: no times, heart rates, recovery values, names or goals.
 */
@Service
class RaceService(
    private val activityRepository: ActivityRepository,
    private val garminRepository: GarminRepository,
    private val bestEffortService: BestEffortService,
    private val performanceService: PerformanceService,
    private val loadService: LoadService,
    private val recoveryService: RecoveryService,
    private val goalRepository: RaceGoalRepository,
    private val properties: GarminProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config: RaceConfig get() = properties.race.toConfig()
    private val halvesCache = ConcurrentHashMap<Long, Halves>()

    fun today(): LocalDate = LocalDate.now(clock.withZone(ZONE))

    private class Base(
        val today: LocalDate,
        val activities: List<Activity>,
        val runs: List<RaceRun>,
        val maxHr: Int?,
    )

    /** Only found results are cached: a stream that is not downloaded yet may arrive later. */
    private fun halves(a: Activity): Halves? = halvesCache[a.id]
        ?: activityRepository.findStreams(a.id)?.let { Pacing.halves(it.time, it.distance) }?.also { halvesCache[a.id] = it }

    private fun base(): Base {
        val today = today()
        val acts = activityRepository.findAll().filter { it.isRun() && !it.isFlagged && !it.isManual }.sortedBy { it.startDate }
        val laps = activityRepository.findLapsForActivities(acts.map { it.id })
        val maxHr = HrZones.estimateMaxHrForRuns(acts, java.time.ZonedDateTime.now(clock))
        val runs = acts.map { a ->
            val elev = TerrainClass.elevationPerKm(a)
            RaceRun(
                id = a.id, date = ActivityTime.localDate(a), startEpochSeconds = a.startDate.toEpochSecond(), name = a.name,
                distanceMeters = a.distance.toDouble(), movingSeconds = a.movingTime, elapsedSeconds = a.elapsedTime,
                avgHr = a.averageHeartrate?.toDouble(), workoutType = a.workoutType, isTrainer = a.isTrainer, elevationPerKm = elev,
                sessionType = SessionClassifier.classify(laps[a.id].orEmpty(), a.workoutType, elev),
                efforts = if (a.isTrainer) emptyMap() else BestEffortFinder.STANDARD_DISTANCES.mapNotNull { (_, m) -> bestEffortService.effort(a, m)?.let { m to it.seconds } }.toMap(),
                halves = if (a.workoutType == Races.RACE) halves(a) else null,
            )
        }
        return Base(today, acts, runs, maxHr)
    }

    private fun garminLtHr(): Int? =
        garminRepository.findHeartRateZones().firstOrNull { it.sport.equals("RUNNING", true) && it.lactateThresholdHr != null }?.lactateThresholdHr

    fun goals(): List<RaceGoal> = goalRepository.findAll()

    // ------------------------------------------------------------------ overview

    fun overview(w: RaceWindows = RaceWindows.DEFAULT): RaceOverview {
        val started = System.nanoTime()
        val b = base()
        val cfg = config
        val today = b.today
        val perf = performanceService.report()
        val snapshot = loadService.snapshot()
        val goals = goalRepository.findAll()

        val outcomes = Accuracy.outcomes(b.runs, w.anchorDays, b.maxHr)
        val accuracy = Accuracy.summary(outcomes, cfg)
        val anchors = Anchors.collect(b.runs, today.plusDays(1), w.anchorDays, b.maxHr)

        val focusGoal = goals.filter { !it.date.isBefore(today) }.minByOrNull { it.date }
        val distances = (Prediction.STANDARD + goals.filter { !it.date.isBefore(today) }.map { it.distanceMeters }).distinct().sorted()
        fun forecast(distance: Double, from: List<RaceAnchor> = anchors): DistanceForecast {
            val p = Prediction.predict(distance, from)
            val own = p?.let { Accuracy.range(it.rawSeconds, accuracy) }
            val low = own?.first ?: p?.lowSeconds
            val high = own?.second ?: p?.highSeconds
            return DistanceForecast(
                distance, distance in Prediction.STANDARD, p, low?.let { RacePredictorRound.round(it) }, high?.let { RacePredictorRound.round(it) },
                if (own != null) RangeBasis.OWN_ERRORS else RangeBasis.ANCHOR_SPREAD, Prediction.criticalSpeedSeconds(distance, perf.cs.outcome),
            )
        }
        val forecasts = distances.map { forecast(it) }
        val violations = Prediction.paceViolations(forecasts.mapNotNull { it.prediction }.filter { it.targetMeters in Prediction.STANDARD })

        val horizonWeeks = maxOf(w.chartWeeks, 52)
        val firstMonday = WeeklyVolume.weekStart(today).minusWeeks(horizonWeeks.toLong())
        val weekly = Prediction.weekly(b.runs, firstMonday, WeeklyVolume.weekStart(today), distances, w.anchorDays, b.maxHr)

        val goalViews = goals.map { g ->
            val past = g.date.isBefore(today)
            val f = if (past) null else forecasts.firstOrNull { it.distanceMeters == g.distanceMeters } ?: forecast(g.distanceMeters)
            val weeks = (ChronoUnit.DAYS.between(today, g.date) / 7).toInt()
            val target = g.targetSeconds
            val feas = if (f?.prediction != null && target != null && f.rangeLowSeconds != null && f.rangeHighSeconds != null) {
                GoalFeasibility.evaluate(
                    target.toDouble(), g.distanceMeters, f.prediction.rawSeconds, f.rangeLowSeconds, f.rangeHighSeconds, weeks,
                    weekly.map { it.seconds[g.distanceMeters] }, cfg,
                )
            } else null
            GoalView(g, ChronoUnit.DAYS.between(today, g.date), past, f, feas, Races.match(g, b.runs, cfg))
        }
        val focus = goalViews.firstOrNull { it.goal.id == focusGoal?.id }

        val plan = focus?.let { f ->
            val target = f.goal.targetSeconds?.toDouble() ?: f.forecast?.prediction?.rawSeconds
            if (target == null) null else PlanView(
                target, if (f.goal.targetSeconds != null) "je doeltijd" else "het midden van je voorspelling", f.goal.distanceMeters,
                PacePlan.even(target, f.goal.distanceMeters), PacePlan.calmStart(target, f.goal.distanceMeters, cfg.calmStartPct),
                PacePlan.calmMeters(f.goal.distanceMeters), cfg.calmStartPct,
            )
        }

        val racesWithHalves = outcomes.filter { !it.trail && it.race.halves != null }
        val pacing = PacingView(
            Pacing.summary(racesWithHalves.map { it.race.halves!! }, cfg),
            racesWithHalves.map { PacingRow(it.race.id, it.race.date, it.race.name, it.race.distanceMeters, it.race.halves!!) }.sortedByDescending { it.date },
        )

        // fitness block (#50)
        val refDistance = focus?.goal?.distanceMeters ?: 5000.0
        val now = Prediction.predict(refDistance, anchors)?.rawSeconds
        val before = Prediction.predict(refDistance, Anchors.collect(b.runs, today.minusWeeks(w.trendWeeks.toLong()), w.anchorDays, b.maxHr))?.rawSeconds
        val goalSpeed = focus?.goal?.targetSeconds?.let { focus.goal.distanceMeters / it }
        val specificSeconds = goalSpeed?.let { specific(b, today, w.specificWeeks, it, cfg) }
        val longest = b.runs.filter { !it.date.isBefore(today.minusWeeks(w.specificWeeks.toLong())) && !it.date.isAfter(today) }.maxOfOrNull { it.distanceMeters }
        val lastAnchor = anchors.filter { (refDistance / it.distanceMeters) in 0.4..2.5 }.maxOfOrNull { it.date }
        val fit = perf.fitness
        val fitness = RaceReadiness.fitness(
            FitnessInput(
                now, before, w.trendWeeks, fit.favourable, fit.unfavourable, fit.indicators.size - fit.insufficient, specificSeconds, w.specificWeeks,
                focus?.goal?.distanceMeters, longest, lastAnchor?.let { ChronoUnit.DAYS.between(it, today).toInt() },
            ),
            cfg, hasGoal = focus?.goal?.targetSeconds != null,
        )
        val recovery = snapshot.overview.readiness?.types?.firstOrNull { it.type == ReadinessType.RACE }

        val raceWeek = focus?.let { raceWeek(it, w, snapshot) }
        val hiddenDays = if (focus != null && raceWeek == null) focus.daysToGo else null

        val overview = RaceOverview(
            RaceAlgorithm.VERSION, today, w, b.runs.size, outcomes.size, b.maxHr, anchors, forecasts, violations, outcomes, accuracy, weekly,
            goalViews, focus, plan, pacing, fitness, recovery, specificSeconds, raceWeek, hiddenDays, Candidates.find(b.runs),
        )
        log.info(
            "Race overview {}: runs={} races={} anchors={} goals={} predictions={} ms={}", RaceAlgorithm.VERSION, b.runs.size, outcomes.size,
            anchors.size, goals.size, forecasts.count { it.prediction != null }, (System.nanoTime() - started) / 1_000_000,
        )
        return overview
    }

    private fun specific(b: Base, today: LocalDate, weeks: Int, goalSpeedMs: Double, cfg: RaceConfig): Double {
        val from = today.minusWeeks(weeks.toLong())
        return b.activities.filter { ActivityTime.localDate(it).let { d -> !d.isBefore(from) && !d.isAfter(today) } && !it.isTrainer }.sumOf { a ->
            activityRepository.findStreams(a.id)?.let { Specificity.secondsAtOrFaster(it.time, it.distance, goalSpeedMs, cfg.paceTolerancePct) } ?: 0.0
        }
    }

    private fun dutch(k: ReadinessKind) = when (k) {
        ReadinessKind.HRV -> "HRV"
        ReadinessKind.RESTING_HR -> "rusthartslag"
        ReadinessKind.SLEEP -> "slaap"
        ReadinessKind.BODY_BATTERY -> "Body Battery"
    }

    private fun raceWeek(focus: GoalView, w: RaceWindows, snapshot: LoadSnapshot): RaceWeekView? {
        val today = snapshot.today
        if (!RaceWeek.visible(focus.goal.date, today, w.weekCheckDays)) return null
        val (recDate, _) = recoveryService.defaultDate()
        val report: RecoveryReport? = runCatching { recoveryService.report(recDate) }.getOrNull()
        val components = report?.readiness?.components.orEmpty().map {
            WeekCheckComponent(it.kind.name, dutch(it.kind), it.value, it.median, it.deviation, it.band?.name)
        }
        val from = focus.goal.date.minusDays(w.weekCheckDays.toLong())
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.mapNotNull { d ->
            val r = runCatching { recoveryService.report(d) }.getOrNull() ?: return@mapNotNull null
            val s = r.readiness.summary
            WeekCheckDay(d, s.componentsWithData, s.deviating.size, s.deviating.count { it.band == DeviationBand.STRONG }, s.deviating.map { dutch(it.kind) })
        }.toList()
        val daily = DailyLoad.trimpMap(snapshot.days.filter { it.date != today })
        val signals = snapshot.overview.signals?.set
        return RaceWeekView(
            focus.goal, focus.daysToGo, report?.date, components, days, snapshot.overview.readiness?.sleepShortfall, RaceWeek.loadPct(daily, today),
            signals?.takeIf { it.withData >= 3 }?.active, signals?.signals?.filter { it.active == true }?.map { it.key.label }.orEmpty(),
            snapshot.overview.readiness?.daysSinceHard,
        )
    }

    // ------------------------------------------------------------------ history

    private fun recoveryMorning(date: LocalDate): String? {
        val report = runCatching { recoveryService.report(date) }.getOrNull() ?: return null
        val s = report.readiness.summary
        return when {
            !s.available -> "te weinig herstelwaarden die ochtend om een samenvatting te geven"
            s.deviating.isEmpty() -> "alle herstelwaarden binnen je norm"
            else -> "${s.deviating.size} van ${s.componentsWithData} herstelwaarden wijken af van je norm (${s.deviating.joinToString(", ") { dutch(it.kind) }})"
        }
    }

    fun history(w: RaceWindows = RaceWindows.DEFAULT): RaceHistoryReport {
        val started = System.nanoTime()
        val b = base()
        val cfg = config
        val snapshot = loadService.snapshot()
        val daily = DailyLoad.trimpMap(snapshot.days.filter { it.date != b.today })
        val outcomes = Accuracy.outcomes(b.runs, w.anchorDays, b.maxHr)
        val accuracy = Accuracy.summary(outcomes, cfg)
        val lt = garminLtHr()

        val taper = outcomes.filter { !it.trail }.map { TaperRow(it, Taper.compute(daily, it.race.date, w.taperShortDays, w.taperLongDays)) }
        val taperSummary = Taper.summary(taper.mapNotNull { t ->
            val x = t.taper.load7Pct
            val y = t.outcome.errorPct
            if (x != null && y != null) x to y else null
        }, cfg)

        val post = outcomes.sortedByDescending { it.race.startEpochSeconds }.map { o ->
            val anchors = Anchors.collect(b.runs, o.race.date, w.anchorDays, b.maxHr).filter { a ->
                o.race.distanceMeters > 0 && (o.race.distanceMeters / a.distanceMeters) in 0.4..2.5
            }
            PostRaceAnalysis.analyze(PostRaceContext(o, b.runs, anchors, Taper.compute(daily, o.race.date, w.taperShortDays, w.taperLongDays), recoveryMorning(o.race.date), lt))
        }

        val prep = Retrospective.rows(outcomes, snapshot.sessions, daily, w.retroWeeks, w.taperShortDays, w.taperLongDays)
        val racesWithHalves = outcomes.filter { !it.trail && it.race.halves != null }
        val pacing = PacingView(
            Pacing.summary(racesWithHalves.map { it.race.halves!! }, cfg),
            racesWithHalves.map { PacingRow(it.race.id, it.race.date, it.race.name, it.race.distanceMeters, it.race.halves!!) }.sortedByDescending { it.date },
        )
        log.info("Race history {}: races={} predicted={} ms={}", RaceAlgorithm.VERSION, outcomes.size, outcomes.count { it.errorPct != null }, (System.nanoTime() - started) / 1_000_000)
        return RaceHistoryReport(RaceAlgorithm.VERSION, b.today, w, outcomes, accuracy, post, taper, taperSummary, prep, Retrospective.groups(prep, cfg), pacing)
    }

    /** Fills the per-activity caches right after start-up (best efforts, halves) so the first request is fast. Never fails the app. */
    @EventListener(ApplicationReadyEvent::class)
    fun warmUpInBackground() {
        if (!properties.race.warmUp) return
        Thread({ warmUp() }, "race-warmup").apply { isDaemon = true; start() }
    }

    fun warmUp(): Boolean = try {
        base()
        true
    } catch (e: Exception) {
        log.warn("Race warm-up failed: {}", e.javaClass.simpleName)
        false
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Brussels")
    }
}

internal object RacePredictorRound {
    fun round(s: Double) = com.running.strava.analysis.RacePredictor.roundSeconds(s)
}
