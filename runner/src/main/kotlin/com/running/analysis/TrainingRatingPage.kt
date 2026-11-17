package com.running.analysis

import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.Format
import com.running.strava.analysis.TrainingAssessment
import com.running.strava.analysis.TrainingAssessment.Caveat
import com.running.strava.analysis.TrainingAssessment.RatingLevel
import com.running.strava.analysis.TrainingAssessment.RatingTone
import com.running.strava.analysis.performance.TerrainClass
import com.running.strava.analysis.speedMs
import com.running.strava.domain.Activity
import com.running.strava.domain.SessionType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One tile with a judged aspect of the training. */
data class RatingPointView(
    val label: String,
    val value: String,
    val sub: String?,
    val tone: Tone,
    val estimate: Boolean = false,
    val tip: String? = null,
)

data class RatingRepRow(
    val number: Int,
    val distance: String,
    val time: String,
    val pace: String,
    val hr: String,
    val maxHr: String,
)

data class RatingLoad(
    val tiles: List<RatingPointView>,
    val lines: List<String>,
)

data class RatingHero(
    val levelCss: String,
    val levelLabel: String,
    val levelTone: Tone,
    val headline: String,
    val sub: String,
    val facts: List<Pair<String, String>>,
    val points: List<RatingPointView>,
    val conclusion: List<String>,
    val method: List<String>,
    val footnote: String,
)

data class TrainingRatingView(
    val sessionLabel: String,
    val hero: RatingHero,
    val repNote: String?,
    val repRows: List<RatingRepRow>,
    val load: RatingLoad,
    val caveats: List<String>,
)

/**
 * Dutch wording for the training assessment page. The numbers come from [TrainingAssessment]; this object only
 * names and explains them. It never turns the data into a promise about fitness, injury or the value of a session:
 * the goal, the feeling, the weather and the rest of the training plan are not in the data.
 */
object TrainingRatingPageBuilder {

    private val ROOT = Locale.ROOT

    private fun f(v: Double, digits: Int = 1) = "%.${digits}f".format(ROOT, v)
    private fun pct(v: Double, digits: Int = 1) = "${f(v, digits)} %"
    private fun signedPct(v: Double, digits: Int = 1) = "%+.${digits}f %%".format(ROOT, v)
    private fun pace(seconds: Double?) = Format.paceFromSeconds(seconds)

    /** Pace range with one shared unit: "5:18 – 5:30 /km". */
    private fun paceRange(from: Double?, to: Double?) = "${Format.paceFromSeconds(from, unit = false)} – ${pace(to)}"
    private fun km(meters: Double, digits: Int = 2) = "%.${digits}f km".format(ROOT, meters / 1000)
    private fun meters(m: Double) = "${(m / 10).roundToInt() * 10} m"

    private fun tone(t: RatingTone) = when (t) {
        RatingTone.GOOD -> Tone.GOOD
        RatingTone.ATTENTION -> Tone.UNCERTAIN
        RatingTone.BAD -> Tone.BAD
        RatingTone.UNKNOWN -> Tone.NONE
    }

    private fun level(r: TrainingAssessment.Result): Triple<String, String, Tone> = when (r.level) {
        RatingLevel.SOUND -> Triple("hero-ok", "Uitvoering klopt", Tone.GOOD)
        RatingLevel.MIXED -> Triple("hero-warn", "Aandachtspunt", Tone.UNCERTAIN)
        RatingLevel.OFF -> Triple("hero-alert", "Wijkt af", Tone.BAD)
        RatingLevel.INSUFFICIENT -> Triple("hero-na", "Geen oordeel", Tone.NONE)
    }

    fun sessionLabel(t: SessionType): String = when (t) {
        SessionType.RACE -> "wedstrijd"
        SessionType.INTERVAL -> "intervaltraining"
        SessionType.TEMPO -> "tempotraining"
        SessionType.STRIDES -> "duurloop met versnellingen"
        SessionType.STEADY -> "duurloop zonder herkenbare structuur"
    }

    fun build(activity: Activity, r: TrainingAssessment.Result): TrainingRatingView {
        val (css, label, tone) = level(r)
        val facts = facts(activity)
        val points = when (r.sessionType) {
            SessionType.INTERVAL -> intervalPoints(r)
            SessionType.TEMPO -> tempoPoints(r)
            SessionType.RACE -> racePoints(r)
            SessionType.STEADY, SessionType.STRIDES -> steadyPoints(r)
        }
        val conclusion = conclusion(r)
        return TrainingRatingView(
            sessionLabel = sessionLabel(r.sessionType),
            hero = RatingHero(
                levelCss = css,
                levelLabel = label,
                levelTone = tone,
                headline = headline(activity, r),
                sub = sub(r),
                facts = facts,
                points = points,
                conclusion = conclusion,
                method = method(r),
                footnote = "Deze beoordeling gaat over de uitvoering van deze training, niet over je fitheid, je schema of je gevoel. " +
                    "Verband, geen oorzaak.",
            ),
            repNote = r.interval?.takeIf { it.reps.isNotEmpty() }?.let { repNote(it) },
            repRows = r.interval?.reps.orEmpty().map {
                RatingRepRow(
                    number = it.number,
                    distance = meters(it.distanceMeters),
                    time = Format.duration(it.seconds),
                    pace = pace(it.paceSecondsPerKm),
                    hr = it.avgHr?.let { h -> Format.hr(h, unit = false) } ?: "-",
                    maxHr = it.maxHr?.let { h -> Format.hr(h, unit = false) } ?: "-",
                )
            },
            load = load(r),
            caveats = caveats(r),
        )
    }

    // ---------------------------------------------------------------- facts and headline

    private fun facts(a: Activity) = listOf(
        "Datum" to PerfDates.day(ActivityTime.localDate(a)),
        "Afstand" to km(a.distance.toDouble()),
        "Duur" to Format.duration(a.movingTime),
        "Gem. tempo" to Format.pace(a.speedMs()),
        "Gem. HR" to Format.hr(a.averageHeartrate),
        "Hoogtemeters" to "%.0f m".format(ROOT, a.totalElevationGain),
    )

    private fun headline(a: Activity, r: TrainingAssessment.Result): String {
        if (r.level == RatingLevel.INSUFFICIENT) {
            return when {
                r.caveats.contains(Caveat.MANUAL_ENTRY) -> "Handmatig ingevoerd: geen oordeel"
                r.caveats.contains(Caveat.TREADMILL) -> "Loopband: geen oordeel"
                r.lapCount <= 1 -> "Geen rondes: geen oordeel"
                else -> "${sessionLabel(r.sessionType).replaceFirstChar { it.uppercase() }}: geen oordeel"
            }
        }
        val interval = r.interval
        val tempo = r.tempo
        return when (r.sessionType) {
            SessionType.INTERVAL -> {
                if (interval == null) return "Intervaltraining"
                val reps = repLabel(interval)
                when (r.level) {
                    RatingLevel.SOUND -> "$reps, gelijkmatig gelopen"
                    RatingLevel.MIXED -> "$reps, wisselend tempo"
                    RatingLevel.OFF -> if ((interval.halves.fadePct ?: 0.0) > TrainingAssessment.REP_FADE_BAD_PCT) {
                        "$reps, tempo zakt weg"
                    } else {
                        "$reps, wisselend tempo"
                    }
                    RatingLevel.INSUFFICIENT -> if (interval.ladder) "Intervaltraining met ${interval.repCount} reps" else reps
                }
            }
            SessionType.TEMPO -> {
                if (tempo == null) return "Tempotraining"
                val block = "Tempoblok van ${km(tempo.meters)}"
                when {
                    r.level == RatingLevel.SOUND -> "$block, gelijkmatig"
                    r.level == RatingLevel.OFF && (tempo.halves.decouplingPct ?: 0.0) > TrainingAssessment.DECOUPLING_BAD_PCT ->
                        "$block, hartslag loopt sterk op"
                    r.level == RatingLevel.OFF -> "$block, tempo zakt weg"
                    r.level == RatingLevel.MIXED && (tempo.halves.decouplingPct ?: 0.0) > TrainingAssessment.DECOUPLING_ATTENTION_PCT ->
                        "$block, hartslag loopt op"
                    r.level == RatingLevel.MIXED -> "$block, tempo zakt wat weg"
                    else -> block
                }
            }
            SessionType.RACE -> {
                val race = r.race
                val fade = race?.halves?.fadePct
                when {
                    r.level == RatingLevel.SOUND -> "Wedstrijd, gelijkmatig gepacet"
                    fade != null && fade > 0 -> "Wedstrijd: tweede helft ${pct(fade, 0)} trager"
                    fade != null -> "Wedstrijd: tweede helft ${pct(abs(fade), 0)} sneller"
                    else -> "Wedstrijd van ${km(r.distanceMeters)}"
                }
            }
            SessionType.STEADY, SessionType.STRIDES -> {
                val steady = r.steady
                val base = "Duurloop van ${km(r.distanceMeters)}"
                when {
                    r.sessionType == SessionType.STRIDES -> "Duurloop met versnellingen"
                    r.level == RatingLevel.SOUND -> "$base, gelijkmatig"
                    r.level == RatingLevel.MIXED && (steady?.halves?.decouplingPct ?: 0.0) > TrainingAssessment.DECOUPLING_ATTENTION_PCT ->
                        "$base, hartslag loopt op"
                    r.level == RatingLevel.MIXED || r.level == RatingLevel.OFF -> "$base, wisselend tempo"
                    else -> base
                }
            }
        }
    }

    private fun repLabel(i: TrainingAssessment.Interval): String {
        val size = when {
            i.timeBased && i.seconds != null -> Format.duration(i.seconds)
            i.distanceMeters != null -> meters(i.distanceMeters ?: 0.0)
            else -> "rep"
        }
        return "${i.repCount} × $size"
    }

    private fun sub(r: TrainingAssessment.Result): String {
        val goal = "De app kent het doel van deze training niet: of ze nuttig was voor je ontwikkeling hangt van je schema af."
        if (r.level == RatingLevel.INSUFFICIENT) {
            return "Daarom geeft de app geen oordeel: ${caveatReasons(r, limit = 2)}. $goal"
        }
        return when (r.sessionType) {
            SessionType.INTERVAL -> {
                val i = r.interval ?: return goal
                val spread = i.spreadPct
                when (i.consistency) {
                    RatingTone.GOOD -> "De reps lagen binnen ${pct(spread ?: 0.0)} van elkaar (${paceRange(i.paceMin, i.paceMax)}) en de pauzes waren rustig. $goal"
                    RatingTone.ATTENTION -> "De reps liepen ${pct(spread ?: 0.0)} uiteen (${paceRange(i.paceMin, i.paceMax)}). Dat is meer dan de 3 % die de app gelijkmatig noemt. $goal"
                    RatingTone.BAD -> "De reps liepen ${pct(spread ?: 0.0)} uiteen (${paceRange(i.paceMin, i.paceMax)}), met een duidelijke verandering in tempo of hartslag. $goal"
                    RatingTone.UNKNOWN -> "De app kan de gelijkmatigheid van deze reps niet beoordelen: ${consistencyReason(r)}. $goal"
                }
            }
            SessionType.TEMPO -> {
                val t = r.tempo ?: return goal
                val dec = t.halves.decouplingPct
                val fade = t.halves.fadePct
                when {
                    dec != null && dec > TrainingAssessment.DECOUPLING_ATTENTION_PCT ->
                        "Je liep ${km(t.meters)} aan ${pace(t.paceSecondsPerKm)}, maar je efficiëntie daalde ${pct(dec)} tussen de helften. $goal"
                    fade != null && fade > TrainingAssessment.PACE_FADE_ATTENTION_PCT ->
                        "Je liep ${km(t.meters)} aan ${pace(t.paceSecondsPerKm)}; de tweede helft was ${pct(fade)} trager. $goal"
                    else -> "Je liep ${km(t.meters)} aan ${pace(t.paceSecondsPerKm)} met een gelijkmatig tempo en een stabiele hartslag. $goal"
                }
            }
            SessionType.RACE -> {
                val race = r.race
                val fade = race?.halves?.fadePct
                when {
                    fade == null -> "De app kon de twee helften van deze wedstrijd niet vergelijken. $goal"
                    fade > 0 -> "Eerste helft ${pace(race!!.firstHalfPace)}, tweede helft ${pace(race.secondHalfPace)}: ${pct(fade)} trager. $goal"
                    else -> "Eerste helft ${pace(race!!.firstHalfPace)}, tweede helft ${pace(race.secondHalfPace)}: ${pct(abs(fade))} sneller. $goal"
                }
            }
            SessionType.STEADY, SessionType.STRIDES -> {
                val s = r.steady
                val even = s?.evenness
                val dec = s?.halves?.decouplingPct
                when {
                    even == RatingTone.GOOD && dec != null && dec <= TrainingAssessment.DECOUPLING_ATTENTION_PCT ->
                        "Je tempo bleef gelijkmatig (${pct(s.spreadPct ?: 0.0)} spreiding) en je hartslag liep ${pct(abs(dec))} op tussen de helften. $goal"
                    even == RatingTone.ATTENTION || even == RatingTone.BAD ->
                        "Je tempo schommelde ${pct(s?.spreadPct ?: 0.0)} tussen de kilometers. Dat kan aan het terrein, de wind of je planning liggen. $goal"
                    dec != null && dec > TrainingAssessment.DECOUPLING_ATTENTION_PCT ->
                        "Je tempo bleef gelijk, maar je hartslag liep ${pct(dec)} op tussen de helften. $goal"
                    else -> "De app kon geen gelijkmatigheid of verval berekenen voor deze duurloop. $goal"
                }
            }
        }
    }

    private fun consistencyReason(r: TrainingAssessment.Result): String = when {
        r.caveats.contains(Caveat.SHORT_REPS) -> "de reps duren korter dan 60 seconden, waardoor GPS en hartslag te ruw zijn"
        r.caveats.contains(Caveat.LADDER) -> "de reps hebben verschillende lengtes (ladder of piramide)"
        r.caveats.contains(Caveat.HILLY) -> "het parcours is heuvelachtig, dus tempoverschillen komen uit het terrein"
        r.caveats.contains(Caveat.FEW_REPS) -> "er zijn minder dan drie reps"
        else -> "er zijn te weinig bruikbare rondes"
    }

    /** Short reason(s) why a verdict is missing, most important first. */
    private fun caveatReasons(r: TrainingAssessment.Result, limit: Int? = null): String {
        val priority = listOf(Caveat.MANUAL_ENTRY, Caveat.TREADMILL, Caveat.NO_LAPS, Caveat.NO_HR)
        val why = priority.mapNotNull { c -> if (r.caveats.contains(c)) caveatReason(c) else null }
            .ifEmpty { r.caveats.mapNotNull { caveatReason(it) } }
            .distinct()
            .let { if (limit == null) it else it.take(limit) }
        return if (why.isEmpty()) "de data is te beperkt" else why.joinToString("; ")
    }

    private fun caveatReason(c: Caveat): String? = when (c) {
        Caveat.NO_LAPS -> "er zijn geen rondes, dus de opbouw en het verval zijn niet te berekenen"
        Caveat.TREADMILL -> "op een loopband zijn tempo en afstand onbetrouwbaar"
        Caveat.MANUAL_ENTRY -> "de training is handmatig ingevoerd, zonder gemeten rondes of hartslag"
        Caveat.NO_HR -> "er is geen hartslag, dus de inspanning is niet van het tempo te scheiden"
        Caveat.VERY_SHORT -> "de training duurt korter dan 15 minuten"
        else -> null
    }

    // ---------------------------------------------------------------- points

    private fun intervalPoints(r: TrainingAssessment.Result): List<RatingPointView> {
        val i = r.interval ?: return emptyList()
        val recoveryUnit = when {
            i.recoveryLaps == 0 -> "geen pauze herkend"
            i.recoveryIsFloat -> "float, bewust matig"
            else -> "rustig"
        }
        return listOfNotNull(
            RatingPointView(
                "Gelijkmatigheid", i.spreadPct?.let { pct(it) } ?: "-",
                if (i.spreadPct != null) "${paceRange(i.paceMin, i.paceMax)}" else consistencyReason(r),
                tone(i.consistency), estimate = true, tip = "gelijkmatigheid",
            ),
            RatingPointView("Reps", repLabel(i), "gemiddeld ${pace(i.paceSecondsPerKm)}", Tone.NONE),
            RatingPointView(
                "Hartslag", i.avgHr?.let { "${Format.hr(it, unit = false)} bpm" } ?: "geen HR",
                hrSub(i.avgHr, i.maxHr, r.maxHrEstimate), Tone.NONE, tip = "max-hartslag",
            ),
            RatingPointView(
                "Herstel", if (i.recoveryLaps == 0) "-" else (i.recoverySeconds?.let { Format.duration(it) } ?: meters(i.recoveryMeters ?: 0.0)),
                if (i.recoveryLaps == 0) "geen herstelronde herkend" else "${pace(i.recoveryPace)} · $recoveryUnit",
                tone(i.recoveryTone), estimate = true, tip = "herstel",
            ),
            RatingPointView(
                "Opbouw", if (i.warmupMeters > 0) "${km(i.warmupMeters)} in" else "geen warming-up",
                if (i.cooldownMeters > 0) "${km(i.cooldownMeters)} uit" else "geen cooling-down herkend",
                Tone.NONE, estimate = true,
            ),
        )
    }

    private fun tempoPoints(r: TrainingAssessment.Result): List<RatingPointView> {
        val t = r.tempo ?: return emptyList()
        val fade = t.halves.fadePct
        return listOfNotNull(
            RatingPointView(
                "Gelijkmatigheid", fade?.let { pct(abs(it)) } ?: "-",
                if (fade != null) "${Format.paceFromSeconds(t.halves.firstPace, unit = false)} → ${pace(t.halves.secondPace)}" else "te weinig rondes",
                if (fade == null) Tone.NONE else if (abs(fade) <= TrainingAssessment.PACE_FADE_ATTENTION_PCT) Tone.GOOD else Tone.UNCERTAIN,
                estimate = true, tip = "gelijkmatigheid",
            ),
            RatingPointView(
                "Tempoblok", km(t.meters), "${Format.duration(t.seconds)} · ${pace(t.paceSecondsPerKm)}", Tone.NONE,
            ),
            RatingPointView(
                "Hartslag", t.avgHr?.let { "${Format.hr(it, unit = false)} bpm" } ?: "geen HR",
                hrSub(t.avgHr, null, r.maxHrEstimate) + (t.halves.hrDriftBpm?.let { " · ${signedBpm(it)} tussen de helften" } ?: ""),
                Tone.NONE, tip = "max-hartslag",
            ),
            RatingPointView(
                "Ontkoppeling", t.halves.decouplingPct?.let { pct(it) } ?: "-",
                decouplingSub(t.halves),
                tone(decouplingTone(t.halves)), estimate = true, tip = "ontkoppeling",
            ),
            RatingPointView(
                "Opbouw", if (t.warmupMeters > 0) "${km(t.warmupMeters)} in" else "geen warming-up",
                if (t.cooldownMeters > 0) "${km(t.cooldownMeters)} uit" else "geen cooling-down herkend",
                Tone.NONE, estimate = true,
            ),
        )
    }

    private fun steadyPoints(r: TrainingAssessment.Result): List<RatingPointView> {
        val s = r.steady ?: return emptyList()
        return listOfNotNull(
            RatingPointView(
                "Gelijkmatigheid", s.spreadPct?.let { pct(it) } ?: "-",
                if (s.spreadPct != null) "${paceRange(s.paceMin, s.paceMax)}" else "te weinig rondes",
                tone(s.evenness), estimate = true, tip = "gelijkmatigheid",
            ),
            RatingPointView(
                "Tempo", pace(r.paceSecondsPerKm), "${km(r.distanceMeters)} in ${Format.duration(r.movingSeconds)}", Tone.NONE,
            ),
            RatingPointView(
                "Hartslag", r.avgHr?.let { "${Format.hr(it, unit = false)} bpm" } ?: "geen HR",
                hrSub(r.avgHr, r.maxHr, r.maxHrEstimate), Tone.NONE, tip = "max-hartslag",
            ),
            RatingPointView(
                "Ontkoppeling", s.halves.decouplingPct?.let { pct(it) } ?: "-",
                decouplingSub(s.halves),
                tone(decouplingTone(s.halves)), estimate = true, tip = "ontkoppeling",
            ),
            RatingPointView(
                "Terrein", "%.0f m/km".format(ROOT, r.elevationPerKm),
                TerrainClass.of(r.elevationPerKm).text, Tone.NONE,
            ),
        )
    }

    private fun racePoints(r: TrainingAssessment.Result): List<RatingPointView> {
        val race = r.race ?: return emptyList()
        val fade = race.halves.fadePct
        return listOfNotNull(
            RatingPointView("Eerste helft", "${pace(race.firstHalfPace)}", null, Tone.NONE),
            RatingPointView("Tweede helft", "${pace(race.secondHalfPace)}", null, Tone.NONE),
            RatingPointView(
                "Verschil", fade?.let { signedPct(it) } ?: "-",
                "positief = tweede helft trager", tone(race.pacing), estimate = true, tip = "pacing",
            ),
            RatingPointView(
                "Hartslag", r.avgHr?.let { "${Format.hr(it, unit = false)} bpm" } ?: "geen HR",
                hrSub(r.avgHr, r.maxHr, r.maxHrEstimate), Tone.NONE, tip = "max-hartslag",
            ),
        )
    }

    private fun decouplingTone(h: TrainingAssessment.Halves): RatingTone {
        val d = h.decouplingPct
        return when {
            d == null || !h.comparable -> RatingTone.UNKNOWN
            d > TrainingAssessment.DECOUPLING_BAD_PCT -> RatingTone.BAD
            d > TrainingAssessment.DECOUPLING_ATTENTION_PCT -> RatingTone.ATTENTION
            else -> RatingTone.GOOD
        }
    }

    private fun decouplingSub(h: TrainingAssessment.Halves): String {
        val d = h.decouplingPct
        return when {
            d == null -> "niet te berekenen"
            !h.comparable -> "tempo verschilde te veel tussen de helften"
            d > TrainingAssessment.DECOUPLING_BAD_PCT -> "boven de vuistregel van 10 %"
            d > TrainingAssessment.DECOUPLING_ATTENTION_PCT -> "boven de vuistregel van 5 %"
            else -> "onder de vuistregel van 5 %"
        }
    }

    private fun hrSub(avg: Double?, max: Double?, maxEstimate: Int?): String {
        if (avg == null) return "geen hartslagband"
        val parts = mutableListOf<String>()
        if (max != null) parts += "max ${Format.hr(max, unit = false)}"
        if (maxEstimate != null) parts += "${f(avg / maxEstimate * 100, 0)} % van je geschatte max (${maxEstimate})"
        else parts += "geen betrouwbare max-HR-schatting"
        return parts.joinToString(" · ")
    }

    private fun signedBpm(v: Double) = "%+.0f bpm".format(ROOT, v)

    private fun repNote(i: TrainingAssessment.Interval): String = buildString {
        append("De app herkent ${i.repCount} reps")
        if (i.timeBased) append(" op tijd") else i.distanceMeters?.let { append(" van ${meters(it)}") }
        if (i.recoveryIsFloat) append(" met floats ertussen (cruise-interval)")
        append(". De rol van elke ronde is een automatische herkenning; de volledige rondes staan op de trainingspagina.")
    }

    // ---------------------------------------------------------------- load

    private fun load(r: TrainingAssessment.Result): RatingLoad {
        val l = r.load
        val avg7 = l.kmAverage7
        val change = l.changePct
        val tiles = buildList {
            add(
                RatingPointView(
                    "Laatste 7 dagen", "${f(l.kmLast7)} km",
                    if (avg7 != null) "gem. ${f(avg7)} km per 7 dagen in de 4 weken ervoor (${signedPct(change ?: 0.0)})"
                    else "geen eerdere runs om mee te vergelijken",
                    Tone.NONE,
                ),
            )
            add(
                RatingPointView(
                    "Runs", "${l.runsLast7}",
                    if (l.hardSessions7 == 0) "geen zware sessie" else "waarvan ${l.hardSessions7} zware ${if (l.hardSessions7 == 1) "sessie" else "sessies"}",
                    Tone.NONE,
                ),
            )
            add(
                RatingPointView(
                    "Langste run", l.longestKm28?.let { "${f(it)} km" } ?: "-",
                    "in de 28 dagen tot deze training", Tone.NONE,
                ),
            )
            add(
                RatingPointView(
                    "Vorige zware sessie",
                    when (val d = l.daysSinceHard) {
                        null -> "geen in 35 dagen"
                        0L -> "zelfde dag"
                        1L -> "1 dag ervoor"
                        else -> "$d dagen ervoor"
                    },
                    "interval, tempo of wedstrijd", Tone.NONE,
                ),
            )
        }
        val lines = buildList {
            if (change != null) {
                add(
                    "Je laatste 7 dagen waren ${pct(abs(change))} ${if (change >= 0) "meer" else "minder"} kilometers dan je gemiddelde van de 4 weken ervoor. " +
                        "Dat is een beschrijving, geen oordeel: de app kent je schema niet.",
                )
            } else {
                add("Er zijn te weinig eerdere runs om je weekvolume mee te vergelijken.")
            }
            if (l.hardSessions7 > 1) {
                add("Deze training is je ${ordinal(l.hardSessions7)} zware sessie in 7 dagen (interval, tempo of wedstrijd).")
            }
            if (l.daysSinceAnyRun != null) {
                add("Tussen deze training en je vorige run zat ${if (l.daysSinceAnyRun == 0L) "geen dag" else "${l.daysSinceAnyRun} dag(en)"}.")
            }
        }
        return RatingLoad(tiles, lines)
    }

    private fun ordinal(n: Int) = when (n) {
        1 -> "1e"
        2 -> "2e"
        3 -> "3e"
        4 -> "4e"
        5 -> "5e"
        6 -> "6e"
        7 -> "7e"
        8 -> "8e"
        9 -> "9e"
        else -> "${n}e"
    }

    // ---------------------------------------------------------------- conclusion and method

    private fun conclusion(r: TrainingAssessment.Result): List<String> {
        if (r.level == RatingLevel.INSUFFICIENT) {
            return listOf(
                "De app geeft geen oordeel over deze training: de gegevens die daarvoor nodig zijn, ontbreken. " +
                    "De lijst onder deze kaart zegt precies welke.",
                "Wat de app niet kan beoordelen: je doel, je gevoel (RPE), het weer, je slaap, pijn of klachten, je schoenen en je voeding. Die staan niet in de data.",
            )
        }
        val lines = mutableListOf<String>()
        when (r.sessionType) {
            SessionType.INTERVAL -> {
                val i = r.interval ?: return emptyList()
                lines += when (i.consistency) {
                    RatingTone.GOOD -> "De ${i.repCount} reps zijn gelijkmatig uitgevoerd: het verschil tussen de snelste en de traagste was ${pct(i.spreadPct ?: 0.0)}. Onder 3 % noemt de app gelijkmatig; boven 6 % wisselend."
                    RatingTone.ATTENTION -> "De reps liepen ${pct(i.spreadPct ?: 0.0)} uiteen. Dat kan door vermoeidheid, terrein of een bewuste opbouw komen; de app ziet alleen het tempo per ronde."
                    RatingTone.BAD -> "De reps liepen ${pct(i.spreadPct ?: 0.0)} uiteen. Zo groot is het verschil dat de training niet meer als één gelijkmatige prikkel telt."
                    RatingTone.UNKNOWN -> "Over de gelijkmatigheid geeft de app geen oordeel: ${consistencyReason(r)}."
                }
                val fade = i.halves.fadePct
                if (fade != null && abs(fade) > TrainingAssessment.REP_FADE_ATTENTION_PCT) {
                    lines += if (fade > 0) {
                        "De tweede helft van de reps was ${pct(fade)} trager dan de eerste. Dat past bij beginnende vermoeidheid of een te hoog starttempo."
                    } else {
                        "De tweede helft van de reps was ${pct(abs(fade))} sneller dan de eerste: je bouwde de training op."
                    }
                }
                if (i.recoveryLaps > 0) {
                    lines += if (i.recoveryIsFloat) {
                        "Tussen de reps liep je ${km(i.recoveryMeters ?: 0.0)} aan ${pace(i.recoveryPace)}: dat is een float (matig tempo) en geen volledige rust. Dat hoort bij cruise-intervallen."
                    } else {
                        "Tussen de reps zat gemiddeld ${Format.duration(i.recoverySeconds)} aan ${pace(i.recoveryPace)}, dus duidelijk rustiger dan de reps."
                    }
                } else {
                    lines += "De app herkent geen aparte herstelrondes tussen de reps. Zonder pauzes is dit eerder een aaneengesloten snelle blok dan een intervaltraining."
                }
                if (i.avgHr != null) {
                    lines += "Je hartslag in de reps was gemiddeld ${Format.hr(i.avgHr, unit = false)} bpm" +
                        (i.maxHr?.let { ", met uitschieters tot ${Format.hr(it, unit = false)} bpm" } ?: "") +
                        (i.halves.hrDriftBpm?.let { "; tussen de eerste en tweede helft ${signedBpm(it)}" } ?: "") +
                        ". Bij korte reps loopt de hartslag achter op de inspanning, dus dat cijfer is een onderschatting van de piekinspanning."
                } else {
                    lines += "Zonder hartslag kan de app de inspanning niet los van het tempo beoordelen."
                }
            }
            SessionType.TEMPO -> {
                val t = r.tempo ?: return emptyList()
                val fade = t.halves.fadePct
                lines += "Je tempoblok was ${km(t.meters)} aan gemiddeld ${pace(t.paceSecondsPerKm)}." +
                    (fade?.let { " De tweede helft was ${pct(abs(it))} ${if (it > 0) "trager" else "sneller"}." } ?: "")
                val dec = t.halves.decouplingPct
                if (dec != null && t.halves.comparable) {
                    lines += "Je efficiëntie (meter per hartslag) daalde ${pct(dec)} tussen de helften. " +
                        if (dec <= TrainingAssessment.DECOUPLING_ATTENTION_PCT) {
                            "Dat blijft onder de vuistregel van 5 %."
                        } else {
                            "Dat is boven de vuistregel van 5 %; warmte, terrein en een korte warming-up kunnen dat ook veroorzaken."
                        }
                } else {
                    lines += "Ontkoppeling is niet berekend: het tempo verschilde te veel tussen de helften, of er is te weinig hartslag."
                }
                if (t.avgHr != null) {
                    lines += "Je hartslag in het blok was gemiddeld ${Format.hr(t.avgHr, unit = false)} bpm" +
                        (t.halves.hrDriftBpm?.let { " (${signedBpm(it)} tussen de helften)" } ?: "") + "."
                }
                lines += "Of dit blok op de juiste intensiteit lag, hangt af van je doel en je drempel. De app kent geen lactaatdrempel en geen schema."
            }
            SessionType.RACE -> {
                val race = r.race
                val fade = race?.halves?.fadePct
                if (race != null && fade != null) {
                    lines += if (fade > 0) {
                        "Je tweede helft was ${pct(fade)} trager dan de eerste (${pace(race.firstHalfPace)} → ${pace(race.secondHalfPace)}). Een positieve split; dat is een pacing-observatie, geen verdict over je wedstrijd."
                    } else {
                        "Je tweede helft was ${pct(abs(fade))} sneller dan de eerste (${pace(race.firstHalfPace)} → ${pace(race.secondHalfPace)}). Een negatieve split."
                    }
                }
                if (r.avgHr != null) {
                    lines += "Je gemiddelde hartslag was ${Format.hr(r.avgHr, unit = false)} bpm" +
                        (r.maxHr?.let { ", met een max van ${Format.hr(it, unit = false)} bpm" } ?: "") + "."
                }
                lines += "Een wedstrijd beoordeelt de app alleen op pacing en hartslag. Of je alles gaf, hoe de benen voelden en wat het parcours deed, zit niet in de data."
            }
            SessionType.STEADY, SessionType.STRIDES -> {
                val s = r.steady
                val spread = s?.spreadPct
                if (s != null && spread != null) {
                    lines += "Je tempo schommelde ${pct(spread)} tussen de kilometers (${paceRange(s.paceMin, s.paceMax)})." +
                        if (s.evenness == RatingTone.GOOD) " Dat is gelijkmatig." else " Dat kan aan het terrein, de wind of je planning liggen."
                }
                val dec = s?.halves?.decouplingPct
                if (s != null && dec != null && s.halves.comparable) {
                    lines += "Je efficiëntie daalde ${pct(dec)} tussen de eerste en tweede helft." +
                        if (dec <= TrainingAssessment.DECOUPLING_ATTENTION_PCT) " Dat blijft onder de vuistregel van 5 %." else " Dat is boven de vuistregel van 5 %; warmte en terrein spelen mee."
                }
                val pctMax = r.avgHrPctMax
                val maxEst = r.maxHrEstimate
                if (pctMax != null && maxEst != null) {
                    val judgement = when {
                        pctMax < 75 -> "Dat hoort bij een rustige duurloop."
                        pctMax < 85 -> "Dat is een stevige duurloop, geen herstel."
                        else -> "Dat is hoog voor een duurloop zonder structuur."
                    }
                    lines += "Je gemiddelde hartslag was ${Format.hr(r.avgHr, unit = false)} bpm, ${f(pctMax, 0)} % van je geschatte max ($maxEst). $judgement"
                } else if (r.avgHr == null) {
                    lines += "Zonder hartslag zegt het tempo alleen niets over je inspanning."
                } else {
                    lines += "Zonder betrouwbare max-HR-schatting kan de app je hartslag niet indelen."
                }
                lines += "Als dit een rustige duurloop moest zijn, telt vooral dat je tempo en hartslag laag bleven. Als het een bewust stevige loop was, zegt deze beoordeling daar niets over."
            }
        }
        lines += "Wat de app niet kan beoordelen: je doel, je gevoel (RPE), het weer, je slaap, pijn of klachten, je schoenen en je voeding. Die staan niet in de data."
        return lines
    }

    private fun method(r: TrainingAssessment.Result): List<String> {
        val lines = mutableListOf<String>()
        lines += "Soort training en opbouw herkent de app uit de rondes (tempo per ronde, met hartslag als controle), niet uit de naam. " +
            "Dat is een heuristiek en kan fout zijn bij heuvels, een loopband, korte reps of GPS-fouten."
        when (r.sessionType) {
            SessionType.INTERVAL -> lines += "Gelijkmatigheid = (traagste rep − snelste rep) / gemiddelde tempo. Vuistregels: tot 3 % gelijkmatig, 3 tot 6 % aandachtspunt, boven 6 % wisselend. " +
                "Bij reps korter dan 60 seconden, minder dan drie reps, een ladder of heuvelachtig terrein geeft de app geen oordeel."
            SessionType.TEMPO, SessionType.STEADY, SessionType.STRIDES -> lines += "Gelijkmatigheid = de spreiding van het tempo over de kilometers. " +
                "Ontkoppeling = het verschil in efficiëntie (meter per hartslag) tussen de eerste en tweede helft. " +
                "Vuistregel: onder 5 % is gebruikelijk, boven 10 % duidelijk. De app berekent ze alleen als het tempo in beide helften binnen 5 % blijft."
            SessionType.RACE -> lines += "Pacing = het tempo van de tweede helft tegenover de eerste. Positief betekent trager, negatief sneller. " +
                "De app vergelijkt de helften op basis van de rondes."
        }
        lines += "Hartslag in % van je max gebruikt een schatting: het 95e percentiel van je hoogste hartslag per run van de laatste 12 maanden. " +
            "Dat is geen max-HR-test; je echte max kan hoger liggen."
        lines += "De laadcontext vergelijkt de laatste 7 dagen met de 28 dagen ervoor. Ze telt runs, zware sessies (interval, tempo of wedstrijd) en de langste run. " +
            "Dat is een beschrijving van je week, geen oordeel en geen blessurerisico."
        lines += "De app rekent met de gemiddelden per ronde, niet met de seconde-per-seconde-stroom. Binnen een ronde kunnen tempo en hartslag dus nog variëren."
        return lines
    }

    // ---------------------------------------------------------------- caveats

    private fun caveats(r: TrainingAssessment.Result): List<String> = r.caveats.mapNotNull {
        when (it) {
            Caveat.NO_LAPS -> "Geen rondes: de app kan de opbouw, de gelijkmatigheid en het verval niet berekenen. Je ziet alleen de samenvatting van Strava."
            Caveat.NO_HR -> "Geen hartslag: de app kan de inspanning niet aan het tempo koppelen."
            Caveat.TREADMILL -> "Loopband: tempo en afstand zijn onbetrouwbaar, dus de app geeft geen beoordeling."
            Caveat.MANUAL_ENTRY -> "Handmatig ingevoerd: er zijn geen gemeten rondes of hartslag."
            Caveat.SHORT_REPS -> "Reps korter dan 60 seconden: GPS en hartslag zijn te ruw voor een oordeel over de gelijkmatigheid."
            Caveat.HILLY -> "Heuvelachtig (meer dan 15 hoogtemeter per km): tempoverschillen komen uit het terrein, niet uit de inspanning."
            Caveat.FEW_REPS -> "Minder dan drie reps: de spreiding zegt te weinig voor een oordeel."
            Caveat.LADDER -> "Ladder of piramide met ongelijke reps: de tempo's zijn niet onderling te vergelijken."
            Caveat.TIME_BASED -> "Tijdsgebaseerde reps: de afstand per rep verschilt, het tempo blijft vergelijkbaar."
            Caveat.VERY_SHORT -> "Korter dan 15 minuten: te kort om iets over een trainingseffect te zeggen."
            Caveat.NO_MAX_HR -> "Geen max-HR-schatting: de app kan je hartslag niet als percentage van je max tonen."
            Caveat.LAP_AVERAGES_ONLY -> null // already explained in the method section
            Caveat.NO_HISTORY -> "Geen eerdere runs gevonden: de laadcontext ontbreekt."
        }
    }
}
