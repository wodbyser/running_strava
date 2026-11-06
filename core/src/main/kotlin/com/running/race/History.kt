package com.running.race

import com.running.load.HardSessions
import com.running.load.LoadSession
import com.running.load.LoadStats
import com.running.load.Rolling
import com.running.load.SessionClass
import java.time.LocalDate
import kotlin.math.abs

/** #54. Load of the days before a race against the average week before those days. Plain percentages, no "best taper". */
data class TaperResult(val load7Pct: Double?, val load14Pct: Double?, val baseWeek: Double?)

object Taper {
    fun compute(daily: Map<LocalDate, Double>, raceDate: LocalDate, shortDays: Int = 7, longDays: Int = 14, minKnownShare: Double = 0.70): TaperResult {
        val end = raceDate.minusDays(1)
        val short = Rolling.window(daily, end, shortDays, minKnownShare)
        val long = Rolling.window(daily, end, longDays, minKnownShare)
        val base = Rolling.window(daily, raceDate.minusDays(longDays.toLong() + 1), 28, minKnownShare).perWeek
        fun pct(sum: Double?, days: Int): Double? = if (sum == null || base == null || base <= 0) null else sum / (days / 7.0) / base * 100
        return TaperResult(pct(short.sum, shortDays), pct(long.sum, longDays), base)
    }

    data class Summary(val n: Int, val enough: Boolean, val needed: Int, val rho: Double?, val pValue: Double?)

    /** Rank correlation of taper % with the result (actual against predicted, %), only from [RaceConfig.minTaperRaces] races. */
    fun summary(pairs: List<Pair<Double, Double>>, cfg: RaceConfig = RaceConfig.DEFAULT): Summary {
        val enough = pairs.size >= cfg.minTaperRaces
        val perm = if (enough) LoadStats.spearmanPermutation(pairs.map { it.first }, pairs.map { it.second }, cfg.resamples, cfg.seed) else null
        return Summary(pairs.size, enough, cfg.minTaperRaces, perm?.statistic, perm?.pValue)
    }
}

data class Factor(val key: String, val title: String, val detail: String)

data class PostRace(
    val race: RaceRun,
    val trail: Boolean,
    val predictedSeconds: Double?,
    val actualSeconds: Double,
    val diffPct: Double?,
    val diffSecondsPerKm: Double?,
    val note: String?,
    val factors: List<Factor>,
)

data class PostRaceContext(
    val outcome: RaceOutcome,
    val allRuns: List<RaceRun>,
    val anchors: List<RaceAnchor>,
    val taper: TaperResult?,
    val recoveryText: String?,
    val garminLtHr: Int?,
)

/** #58. Factors are listed, never called causes. */
object PostRaceAnalysis {
    const val NOTE = "Verband, geen oorzaak. Dit zijn gegevens naast je tijd, niet de reden ervan."
    private val NOMINAL = listOf(5000.0, 10000.0, 21097.5, 42195.0)

    private fun f(v: Double, d: Int = 1) = "%.${d}f".format(java.util.Locale.ROOT, v).replace('.', ',')

    fun analyze(c: PostRaceContext): PostRace {
        val r = c.outcome.race
        val actual = r.movingSeconds.toDouble()
        if (c.outcome.trail) {
            return PostRace(r, true, null, actual, null, null, "Trail, geen vergelijking met een wegvoorspelling.", factorsFor(c))
        }
        val pred = c.outcome.predictedSeconds
        val diff = pred?.let { (actual - it) / it * 100 }
        val perKm = pred?.let { (actual - it) / (r.distanceMeters / 1000) }
        val note = if (pred == null) "Geen voorspelling vóór deze wedstrijd: minder dan ${com.running.strava.analysis.RacePredictor.MIN_ANCHORS} ankers binnen het bereik." else null
        return PostRace(r, false, pred, actual, diff, perKm, note, factorsFor(c))
    }

    private fun factorsFor(c: PostRaceContext): List<Factor> {
        val r = c.outcome.race
        val out = ArrayList<Factor>()

        r.halves?.let {
            val p = it.splitPct
            out += Factor("pacing", "Tempo-verloop", "eerste helft ${com.running.strava.analysis.Format.duration(it.firstSeconds)}, tweede helft ${com.running.strava.analysis.Format.duration(it.secondSeconds)}: " +
                if (abs(p) < 0.5) "praktisch gelijk" else "${f(abs(p))} % ${if (p > 0) "trager" else "sneller"} in de tweede helft")
        }

        val similar = c.allRuns.filter {
            Races.isRace(it) && !Races.isTrail(it) && it.id != r.id && it.date.isBefore(r.date) && it.avgHr != null &&
                it.distanceMeters / r.distanceMeters in 0.7..1.4
        }
        val hr = r.avgHr
        if (hr != null) {
            val own = LoadStats.median(similar.map { it.avgHr!! })
            val lt = c.garminLtHr
            out += Factor(
                "hr", "Hartslag",
                "gemiddeld ${Math.round(hr)} bpm" +
                    (if (own != null && similar.size >= 2) ", mediaan van je eerdere wedstrijden van vergelijkbare lengte ${Math.round(own)} bpm (n = ${similar.size})" else ", te weinig eerdere wedstrijden om mee te vergelijken") +
                    (if (lt != null && lt > 0) "; ${Math.round(hr / lt * 100)} % van de lactaatdrempel-hartslag in Garmin ($lt bpm)" else ""),
            )
        }

        val anchorElev = LoadStats.median(c.anchors.map { it.elevationPerKm })
        out += Factor(
            "elevation", "Hoogteverschil",
            "${f(r.elevationPerKm)} m per km" + (if (anchorElev != null) ", je ankers hadden mediaan ${f(anchorElev)} m per km" else ""),
        )

        val nominal = NOMINAL.minByOrNull { abs(it - r.distanceMeters) }
        if (nominal != null && abs(r.distanceMeters - nominal) <= nominal * 0.10) {
            val diff = (r.distanceMeters / nominal - 1) * 100
            out += Factor("distance", "Gemeten afstand", "${f(r.distanceMeters / 1000, 2)} km gemeten, ${if (abs(diff) < 0.05) "gelijk aan" else "${f(abs(diff))} % ${if (diff < 0) "korter" else "langer"} dan"} ${if (nominal == 21097.5) "21,1" else (nominal / 1000).toInt().toString()} km (de voorspelling is voor de gemeten afstand)")
        }

        c.recoveryText?.let { out += Factor("recovery", "Herstel die ochtend", it) }

        c.taper?.let { t ->
            out += Factor(
                "taper", "Taper",
                if (t.load7Pct == null) "belasting van de week ervoor niet te berekenen (te weinig dagen met hartslag)"
                else "belasting van de week ervoor: ${Math.round(t.load7Pct)} % van je gemiddelde week" + (t.load14Pct?.let { ", twee weken ervoor ${Math.round(it)} %" } ?: ""),
            )
        }

        val warm = c.allRuns.filter {
            it.id != r.id && it.distanceMeters < 3000 && it.date == r.date && !Races.isRace(it) &&
                (r.startEpochSeconds - (it.startEpochSeconds + it.elapsedSeconds)) in 0..7200
        }
        out += Factor("warmup", "Warming-up", if (warm.isEmpty()) "geen aparte run van minder dan 3 km in de 2 uur ervoor" else "${warm.size} aparte run(s) van minder dan 3 km in de 2 uur ervoor")
        return out
    }
}

data class PrepRow(
    val raceId: Long,
    val date: LocalDate,
    val name: String,
    val avgWeeklyKm: Double,
    val avgWeeklyLoad: Double?,
    val longRuns: Int,
    val hardSessions: Int,
    val longestRunKm: Double,
    val taper7Pct: Double?,
    val errorPct: Double?,
)

data class PrepGroup(val label: String, val n: Int, val medians: Map<String, Double?>)

data class PrepGroups(val faster: PrepGroup, val slower: PrepGroup)

/** #59. The weeks before each marked road race. */
object Retrospective {
    const val NOTE = "Verband, geen oorzaak. Je bereidde je misschien anders voor op belangrijke wedstrijden."

    fun rows(
        outcomes: List<RaceOutcome>, sessions: List<LoadSession>, daily: Map<LocalDate, Double>, weeks: Int, taperShort: Int, taperLong: Int,
    ): List<PrepRow> {
        val hardIds = HardSessions.ids(sessions)
        return outcomes.filter { !it.trail }.map { o ->
            val race = o.race
            val from = race.date.minusWeeks(weeks.toLong())
            val runs = sessions.filter { it.isRun && !it.date.isBefore(from) && it.date.isBefore(race.date) }
            val load = Rolling.window(daily, race.date.minusDays(1), weeks * 7).perWeek
            PrepRow(
                race.id, race.date, race.name,
                runs.sumOf { it.distanceMeters } / 1000 / weeks,
                load,
                runs.count { it.cls == SessionClass.LONG },
                runs.count { it.id in hardIds },
                (runs.maxOfOrNull { it.distanceMeters } ?: 0.0) / 1000,
                Taper.compute(daily, race.date, taperShort, taperLong).load7Pct,
                o.errorPct,
            )
        }
    }

    /** Faster than predicted against slower than predicted, only when both groups have [RaceConfig.minGroupRaces] races. */
    fun groups(rows: List<PrepRow>, cfg: RaceConfig = RaceConfig.DEFAULT): PrepGroups? {
        val withResult = rows.filter { it.errorPct != null }
        val fast = withResult.filter { it.errorPct!! < 0 }
        val slow = withResult.filter { it.errorPct!! >= 0 }
        if (fast.size < cfg.minGroupRaces || slow.size < cfg.minGroupRaces) return null
        fun g(label: String, list: List<PrepRow>) = PrepGroup(
            label, list.size, mapOf(
                "km" to LoadStats.median(list.map { it.avgWeeklyKm }),
                "load" to LoadStats.median(list.mapNotNull { it.avgWeeklyLoad }),
                "long" to LoadStats.median(list.map { it.longRuns.toDouble() }),
                "hard" to LoadStats.median(list.map { it.hardSessions.toDouble() }),
                "longest" to LoadStats.median(list.map { it.longestRunKm }),
                "taper" to LoadStats.median(list.mapNotNull { it.taper7Pct }),
            ),
        )
        return PrepGroups(g("Sneller dan voorspeld", fast), g("Trager dan voorspeld", slow))
    }
}
