package com.running.frontend

import com.running.analysis.AiAnalysisService
import com.running.analysis.BestEffortService
import com.running.analysis.NextTrainingParams
import com.running.analysis.PeriodComparisonService
import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.Lap
import com.running.strava.domain.LapClassifier
import com.running.strava.domain.LapKind
import com.running.strava.domain.SessionClassifier
import com.running.strava.domain.SessionType
import com.running.strava.analysis.ActivityTime
import com.running.strava.analysis.Cadence
import com.running.strava.analysis.DateRange
import com.running.strava.analysis.Format
import com.running.strava.analysis.RunAggregates
import com.running.strava.analysis.WeeklyVolume
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaTokenRepository
import com.running.strava.usecase.backfill.BackfillLapsData
import com.running.strava.usecase.fetch.FetchAllHistoricalData
import com.running.strava.usecase.fetch.FetchRemainingData
import com.running.strava.usecase.sync.SyncStravaData
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Controller
class FrontendController(
    private val activityRepository: ActivityRepository,
    private val tokenRepository: StravaTokenRepository,
    private val syncStravaData: SyncStravaData,
    private val fetchAllHistoricalData: FetchAllHistoricalData,
    private val fetchRemainingData: FetchRemainingData,
    private val backfillLapsData: BackfillLapsData,
    private val aiAnalysisService: AiAnalysisService,
    private val periodComparisonService: PeriodComparisonService,
    private val bestEffortService: BestEffortService,
) {

    @GetMapping("/")
    fun dashboard(
        model: Model,
        @RequestParam period: String? = null,
        @RequestParam from: String? = null,
        @RequestParam till: String? = null,
        @RequestParam(name = "weekly") weeklyPeriod: String? = null,
        @RequestParam(name = "weeklyFrom") weeklyFrom: String? = null,
        @RequestParam(name = "weeklyTill") weeklyTill: String? = null,
        @RequestParam(name = "evolution") evolutionPeriod: String? = null,
        @RequestParam(name = "evolutionFrom") evolutionFrom: String? = null,
        @RequestParam(name = "evolutionTill") evolutionTill: String? = null,
    ): String {
        val allActivities = activityRepository.findAll()
        val filtered = filterActivities(allActivities, period, from, till, null)
        val runs = filtered.filter { it.type in runTypes }
        val sorted = runs.sortedByDescending { it.startDate }
        val hasToken = tokenRepository.get() != null
        val syncStatus = activityRepository.getSyncStatus()

        // Weighted aggregates: pace = total distance / total moving time; HR and cadence time-weighted over
        // the runs that have that sensor (null -> "-" instead of NaN).
        val agg = RunAggregates.of(runs)
        val recentActivities = sorted.take(10).map { toActivityRow(it) }
        val pbs = bestEffortService.personalRecords(runs)
        val lastSync = syncStatus.lastSyncAt?.withZoneSameInstant(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) ?: "nooit"

        val today = LocalDate.now()
        val allRuns = allActivities.filter { it.type in runTypes }
        val weeklyRange = presetRange(weeklyPeriod, weeklyFrom, weeklyTill)
        val weeklyRuns = allRuns.filter { weeklyRange.contains(it) }
        val firstRunDay = allRuns.minOfOrNull { ActivityTime.localDate(it) } ?: today
        val weeklyVolume = WeeklyVolume.compute(
            weeklyRuns,
            weeklyRange.from ?: firstRunDay,
            weeklyRange.till ?: today,
        )
        val sortedVolume = weeklyVolume.entries.toList()
        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM")
        model.addAttribute("weeklyLabels", sortedVolume.map { it.key.format(dateFormatter) })
        model.addAttribute("weeklyDistances", sortedVolume.map { Math.round(it.value * 10) / 10.0 })
        model.addAttribute("weeklyWeekStarts", sortedVolume.map { it.key.toString() })

        val evolutionRange = presetRange(evolutionPeriod, evolutionFrom, evolutionTill)
        val evolutionRuns = allRuns.filter { evolutionRange.contains(it) }
        val monthlyTrend = periodComparisonService.buildMonthlyTrend(evolutionRuns)
        model.addAttribute("evolutionMonths", monthlyTrend.map { it.month })
        model.addAttribute("evolutionEasyEf", monthlyTrend.map { it.easyEf })
        model.addAttribute("evolutionIntervalEf", monthlyTrend.map { it.intervalEf })
        model.addAttribute("evolutionEasyRunCount", monthlyTrend.map { it.easyRunCount })
        model.addAttribute("evolutionIntervalRepCount", monthlyTrend.map { it.intervalRepCount })
        model.addAttribute("evolutionIntervalSessionCount", monthlyTrend.map { it.intervalSessionCount })
        model.addAttribute("filterEvolution", evolutionPeriod ?: "all")
        model.addAttribute("filterEvolutionFrom", evolutionFrom ?: "")
        model.addAttribute("filterEvolutionTill", evolutionTill ?: "")

        model.addAttribute("hasToken", hasToken)
        model.addAttribute("hasData", runs.isNotEmpty())
        model.addAttribute("stats", mapOf<String, Any>(
            "totalRuns" to runs.size,
            "totalDistance" to "%.1f".format(agg.totalDistanceMeters / 1000),
            "totalTime" to "%.1f".format(agg.totalMovingTimeSeconds / 3600.0),
            "avgPace" to Format.pace(agg.avgSpeedMs),
            "avgHeartrate" to Format.hr(agg.avgHr, unit = false),
            "hrCoverage" to "${agg.hrCount} van ${runs.size} runs met HR",
            "avgCadence" to (agg.avgCadenceSpm?.let { "%.0f".format(it) } ?: "-"),
            "cadenceCoverage" to "${agg.cadenceCount} van ${runs.size} runs met cadans",
            "lastSync" to lastSync,
        ))
        model.addAttribute("recent", recentActivities)
        model.addAttribute("pbs", pbs.records)
        model.addAttribute("title", "Dashboard")
        model.addAttribute("filterWeekly", weeklyPeriod ?: "all")
        model.addAttribute("filterWeeklyFrom", weeklyFrom ?: "")
        model.addAttribute("filterWeeklyTill", weeklyTill ?: "")
        addFilterAttributes(model, period, from, till, null)

        return "dashboard"
    }

    @GetMapping("/activities")
    fun activities(
        model: Model,
        @RequestParam period: String? = null,
        @RequestParam from: String? = null,
        @RequestParam till: String? = null,
        @RequestParam type: String? = "all",
        @RequestParam sort: String? = null,
        @RequestParam order: String? = null,
        @RequestParam(name = "distanceMin") distanceMin: String? = null,
        @RequestParam(name = "distanceMax") distanceMax: String? = null,
        @RequestParam(name = "durationMin") durationMin: String? = null,
        @RequestParam(name = "durationMax") durationMax: String? = null,
    ): String {
        val allActivities = activityRepository.findAll()
        var filtered = filterActivities(allActivities, period, from, till, type)
        filtered = filterByDistanceAndDuration(filtered, distanceMin, distanceMax, durationMin, durationMax)

        val effectiveSort = sort ?: "date"
        val effectiveOrder = order ?: "desc"

        val comparator: Comparator<Activity> = when (effectiveSort) {
            "name" -> compareBy { it.name.lowercase() }
            "type" -> compareBy { it.type }
            "distance" -> compareBy { it.distance }
            "pace" -> compareBy { it.averageSpeed }
            "avgHr" -> compareBy<Activity> { it.averageHeartrate ?: 0f }
            "maxHr" -> compareBy { it.maxHeartrate ?: 0f }
            "cadence" -> compareBy { it.averageCadence ?: 0f }
            "elevation" -> compareBy { it.totalElevationGain }
            "duration" -> compareBy { it.movingTime }
            "sufferScore" -> compareBy<Activity> { it.sufferScore ?: 0 }
            else -> compareBy<Activity> { it.startDate }
        }

        val sorted = if (effectiveOrder == "asc") {
            filtered.sortedWith(comparator)
        } else {
            filtered.sortedWith(comparator.reversed())
        }

        model.addAttribute("activities", sorted.map { toActivityRow(it) })
        model.addAttribute("total", sorted.size)
        model.addAttribute("title", "Trainingen")
        model.addAttribute("currentSort", effectiveSort)
        model.addAttribute("currentOrder", effectiveOrder)
        model.addAttribute("filterDistanceMin", distanceMin ?: "")
        model.addAttribute("filterDistanceMax", distanceMax ?: "")
        model.addAttribute("filterDurationMin", durationMin ?: "")
        model.addAttribute("filterDurationMax", durationMax ?: "")
        addFilterAttributes(model, period, from, till, type)
        return "activities"
    }

    @GetMapping("/activity/{id}")
    fun activityDetail(@PathVariable id: Long, model: Model): String {
        val activity = activityRepository.findById(id)
            ?: return "redirect:/activities"

        val a = activity
        val detail = mutableMapOf<String, Any>()
        detail["id"] = a.id
        detail["name"] = a.name
        detail["date"] = ActivityTime.local(a).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + " (lokale tijd)"
        detail["type"] = a.type
        detail["typeClass"] = typeClass(a.type)
        detail["distance"] = "%.2f km".format(a.distance / 1000)
        detail["pace"] = Format.pace(a.averageSpeed.toDouble())
        detail["duration"] = Format.duration(a.movingTime)
        detail["elapsed"] = Format.duration(a.elapsedTime)
        detail["avgHr"] = Format.hr(a.averageHeartrate)
        detail["maxHr"] = Format.hr(a.maxHeartrate)
        detail["cadence"] = Cadence.formatSpm(a.averageCadence, a.type)
        detail["elevation"] = "%.0f m".format(a.totalElevationGain)
        // max_speed is m/s: fastest instantaneous pace (Strava, GPS-sensitive)
        detail["maxSpeed"] = Format.pace(a.maxSpeed.toDouble())
        detail["avgWatts"] = a.averageWatts?.let { "%.0f W".format(it) } ?: "-"
        detail["maxWatts"] = a.maxWatts?.let { "%.0f W".format(it) } ?: "-"
        detail["calories"] = a.calories?.let { "%.0f".format(it) } ?: "-"
        detail["sufferScore"] = a.sufferScore?.toString() ?: "-"
        detail["description"] = a.description?.take(500) ?: "-"
        detail["stravaUrl"] = "https://www.strava.com/activities/${a.id}"
        model.addAttribute("a", detail)
        model.addAttribute("title", a.name)

        val laps = activityRepository.findLaps(a.id)
        model.addAttribute("laps", buildLapRows(laps, a))
        model.addAttribute("hasLaps", laps.size > 1)
        model.addAttribute("sessionType", if (laps.size > 1 || a.workoutType == 1) sessionLabel(SessionClassifier.classify(laps, a.workoutType)) else null)

        return "activity"
    }

    @GetMapping("/activity/{id}/export")
    fun exportActivity(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val activity = activityRepository.findById(id)
            ?: return ResponseEntity.notFound().build()
        val laps = activityRepository.findLaps(id)

        val csv = buildString {
            // pace as m:ss per km (the old column held seconds per km under a "min_per_km" header);
            // cadence in steps per minute (both feet); type is an automatic app classification.
            appendLine("lap,distance_m,moving_time_s,pace_min_per_km,avg_hr_bpm,max_hr_bpm,cadence_spm,type_auto")
            val labels = lapLabels(laps, activity)
            laps.forEachIndexed { i, lap ->
                appendLine(listOf(
                    lap.lapIndex,
                    "%.0f".format(lap.distance),
                    lap.movingTime,
                    Format.pace(lap.averageSpeed.toDouble(), unit = false),
                    lap.averageHeartrate?.let { Format.hr(it, unit = false) } ?: "",
                    lap.maxHeartrate?.let { Format.hr(it, unit = false) } ?: "",
                    Cadence.toSpm(lap.averageCadence, activity.type)?.let { "%.0f".format(it) } ?: "",
                    labels[i],
                ).joinToString(","))
            }
        }

        val bytes = csv.toByteArray()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"activity-${id}-intervals.csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(bytes)
    }

    data class LapRow(
        val index: Int,
        val distance: String,
        val duration: String,
        val pace: String,
        val avgHr: String,
        val maxHr: String,
        val cadence: String,
        val type: String,
    )

    private fun buildLapRows(laps: List<Lap>, activity: Activity): List<LapRow> {
        val labels = lapLabels(laps, activity)
        return laps.mapIndexed { i, lap ->
            LapRow(
                index = lap.lapIndex,
                distance = "%.0f m".format(lap.distance),
                duration = Format.duration(lap.movingTime),
                pace = Format.pace(lap.averageSpeed.toDouble()),
                avgHr = Format.hr(lap.averageHeartrate, unit = false),
                maxHr = Format.hr(lap.maxHeartrate, unit = false),
                cadence = Cadence.formatSpm(lap.averageCadence, activity.type, unit = false),
                type = labels[i],
            )
        }
    }

    /** Automatic (heuristic) role per lap; "-" when there is only one lap. */
    private fun lapLabels(laps: List<Lap>, activity: Activity): List<String> {
        if (laps.size < 2) return laps.map { "-" }
        val kinds = LapClassifier.classify(laps, activity.workoutType)
        val race = activity.workoutType == 1
        return kinds.map { kind ->
            when {
                kind == LapKind.NOISE -> "ruis"
                race -> "wedstrijd"
                else -> lapKindLabel(kind)
            }
        }
    }

    private fun lapKindLabel(kind: LapKind): String = when (kind) {
        LapKind.REP -> "interval"
        LapKind.FLOAT -> "float/matig"
        LapKind.TEMPO -> "tempo"
        LapKind.EASY -> "rustig/herstel"
        LapKind.NOISE -> "ruis"
    }

    private fun sessionLabel(t: SessionType): String = when (t) {
        SessionType.RACE -> "wedstrijd (volgens Strava)"
        SessionType.INTERVAL -> "intervaltraining"
        SessionType.TEMPO -> "tempo-/progressieblok"
        SessionType.STRIDES -> "rustige loop met versnellingen"
        SessionType.STEADY -> "gelijkmatige loop (geen structuur herkend)"
    }

    @GetMapping("/pbs")
    fun pbs(
        model: Model,
        @RequestParam period: String? = null,
        @RequestParam from: String? = null,
        @RequestParam till: String? = null,
        @RequestParam type: String? = "run",
    ): String {
        val allActivities = activityRepository.findAll()
        val filtered = filterActivities(allActivities, period, from, till, type)
        val runs = filtered.filter { it.type in runTypes }
        val pbs = bestEffortService.personalRecords(runs)
        model.addAttribute("pbs", pbs.records)
        model.addAttribute("pbInfo", pbs)
        model.addAttribute("title", "Persoonlijke Records")
        addFilterAttributes(model, period, from, till, type)
        return "pbs"
    }

    @PostMapping("/sync")
    fun sync(ra: RedirectAttributes): String {
        val result = syncStravaData.execute()
        val msg = buildString {
            append("Synchronisatie voltooid. ")
            append("${result.newActivities} nieuw, ${result.streamsFetched} streams opgehaald")
            if (result.errors.isNotEmpty()) {
                append(", ${result.errors.size} fout(en)")
            }
        }
        val type = if (result.errors.isEmpty()) "success" else "warning"
        ra.addFlashAttribute("flashMessage", msg)
        ra.addFlashAttribute("flashType", type)
        ra.addFlashAttribute("flashErrors", result.errors.take(20))
        return "redirect:/"
    }

    @PostMapping("/fetch-all")
    fun fetchAll(ra: RedirectAttributes): String {
        val result = fetchAllHistoricalData.execute()
        val msg = buildString {
            append("Alle data opgehaald: ${result.activitiesFetched} activiteiten")
            if (result.errors.isNotEmpty()) {
                append(", ${result.errors.size} fout(en)")
            }
        }
        val type = if (result.errors.isEmpty()) "success" else "warning"
        ra.addFlashAttribute("flashMessage", msg)
        ra.addFlashAttribute("flashType", type)
        ra.addFlashAttribute("flashErrors", result.errors.take(20))
        return "redirect:/"
    }

    @PostMapping("/fetch-remaining")
    fun fetchRemaining(ra: RedirectAttributes): String {
        val result = fetchRemainingData.execute()
        val msg = buildString {
            append("Ontbrekende data opgehaald: ${result.newActivities} nieuw, ${result.streamsFetched} streams")
            if (result.errors.isNotEmpty()) {
                append(", ${result.errors.size} fout(en)")
            }
        }
        val type = if (result.errors.isEmpty()) "success" else "warning"
        ra.addFlashAttribute("flashMessage", msg)
        ra.addFlashAttribute("flashType", type)
        ra.addFlashAttribute("flashErrors", result.errors.take(20))
        return "redirect:/"
    }

    @PostMapping("/backfill-laps")
    fun backfillLaps(ra: RedirectAttributes): String {
        val result = backfillLapsData.execute()
        val msg = buildString {
            append("Intervallen gecontroleerd: ${result.checked} activiteiten, ${result.updated} bijgewerkt met rondes, ${result.skipped} al in orde")
            if (result.errors.isNotEmpty()) {
                append(", ${result.errors.size} fout(en)")
            }
        }
        val type = if (result.errors.isEmpty()) "success" else "warning"
        ra.addFlashAttribute("flashMessage", msg)
        ra.addFlashAttribute("flashType", type)
        ra.addFlashAttribute("flashErrors", result.errors.take(20))
        return "redirect:/"
    }

    @GetMapping("/export")
    fun export(): ResponseEntity<ByteArray> {
        val activities = activityRepository.findAll().sortedBy { it.startDate }
        val json = buildString {
            appendLine("{")
            appendLine("  \"exportedAt\": \"${ZonedDateTime.now()}\",")
            appendLine("  \"totalActivities\": ${activities.size},")
            appendLine("  \"activities\": [")
            activities.forEachIndexed { i, a ->
                val streams = activityRepository.findStreams(a.id)
                val pace = Format.pace(a.averageSpeed.toDouble(), unit = false).takeIf { it != "-" }
                appendLine("    {")
                appendLine("      \"id\": ${a.id},")
                appendLine("      \"name\": ${jsonStr(a.name)},")
                appendLine("      \"startDate\": ${jsonStr(a.startDate.toString())},")
                appendLine("      \"startDateLocal\": ${jsonStr(ActivityTime.local(a).toLocalDateTime().toString())},")
                appendLine("      \"type\": ${jsonStr(a.type)},")
                appendLine("      \"sportType\": ${jsonStr(a.sportType)},")
                appendLine("      \"timezone\": ${jsonStr(a.timezone)},")
                appendLine("      \"distanceKm\": ${"%.3f".format(a.distance / 1000)},")
                appendLine("      \"movingTimeMin\": ${a.movingTime / 60},")
                appendLine("      \"elapsedTimeMin\": ${a.elapsedTime / 60},")
                appendLine("      \"averagePace\": ${jsonStr(pace)},")
                appendLine("      \"averageSpeedMs\": ${a.averageSpeed},")
                appendLine("      \"maxSpeedMs\": ${a.maxSpeed},")
                appendLine("      \"averageHeartrate\": ${a.averageHeartrate ?: "null"},")
                appendLine("      \"maxHeartrate\": ${a.maxHeartrate ?: "null"},")
                appendLine("      \"averageCadence\": ${a.averageCadence ?: "null"},")
                appendLine("      \"averageCadenceSpm\": ${Cadence.toSpm(a.averageCadence, a.type)?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "null"},")
                appendLine("      \"averageWatts\": ${a.averageWatts ?: "null"},")
                appendLine("      \"maxWatts\": ${a.maxWatts ?: "null"},")
                appendLine("      \"weightedAverageWatts\": ${a.weightedAverageWatts ?: "null"},")
                appendLine("      \"kilojoules\": ${a.kilojoules ?: "null"},")
                appendLine("      \"deviceWatts\": ${a.deviceWatts ?: "null"},")
                appendLine("      \"totalElevationGain\": ${a.totalElevationGain},")
                appendLine("      \"elevHigh\": ${a.elevHigh ?: "null"},")
                appendLine("      \"elevLow\": ${a.elevLow ?: "null"},")
                appendLine("      \"calories\": ${a.calories ?: "null"},")
                appendLine("      \"sufferScore\": ${a.sufferScore ?: "null"},")
                appendLine("      \"description\": ${jsonStr(a.description)},")
                appendLine("      \"gearId\": ${jsonStr(a.gearId)},")
                appendLine("      \"hasHeartrate\": ${a.hasHeartrate},")
                appendLine("      \"isTrainer\": ${a.isTrainer},")
                appendLine("      \"isCommute\": ${a.isCommute},")
                appendLine("      \"workoutType\": ${a.workoutType ?: "null"},")
                appendLaps(this, activityRepository.findLaps(a.id), a)
                appendStreams(this, streams)
                append("    }")
                if (i < activities.size - 1) appendLine(",") else appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }

        val bytes = json.toByteArray()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"training-data.json\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(bytes)
    }

    @GetMapping("/ai")
    fun aiPrompt(model: Model): String {
        model.addAttribute("title", "AI-analyse")
        model.addAttribute("prompt", aiAnalysisService.buildAiPrompt())
        return "ai"
    }

    @GetMapping("/ai/download")
    fun aiPromptDownload(): ResponseEntity<ByteArray> {
        val bytes = aiAnalysisService.buildAiPrompt().toByteArray()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ai-prompt.txt\"")
            .contentType(MediaType.TEXT_PLAIN)
            .body(bytes)
    }

    @GetMapping("/next-training")
    fun nextTraining(
        model: Model,
        @RequestParam(name = "distanceKm") distanceKm: String? = null,
        @RequestParam(name = "trainingType") trainingType: String? = null,
        @RequestParam(name = "goalRace") goalRace: String? = null,
        @RequestParam(name = "raceDate") raceDate: String? = null,
        @RequestParam(name = "goalTime") goalTime: String? = null,
        @RequestParam(name = "trainingDate") trainingDate: String? = null,
        @RequestParam(name = "notes") notes: String? = null,
    ): String {
        model.addAttribute("title", "Volgende training")
        model.addAttribute("distanceKm", distanceKm ?: "")
        model.addAttribute("trainingType", trainingType ?: "")
        model.addAttribute("goalRace", goalRace ?: "Halve marathon")
        model.addAttribute("raceDate", raceDate ?: "")
        model.addAttribute("goalTime", goalTime ?: "")
        model.addAttribute("trainingDate", trainingDate ?: "")
        model.addAttribute("notes", notes ?: "")
        return "next-training"
    }

    @GetMapping("/next-training/prompt")
    fun nextTrainingPrompt(
        model: Model,
        @RequestParam(name = "distanceKm") distanceKm: String? = null,
        @RequestParam(name = "trainingType") trainingType: String? = null,
        @RequestParam(name = "goalRace") goalRace: String? = null,
        @RequestParam(name = "raceDate") raceDate: String? = null,
        @RequestParam(name = "goalTime") goalTime: String? = null,
        @RequestParam(name = "trainingDate") trainingDate: String? = null,
        @RequestParam(name = "notes") notes: String? = null,
    ): String {
        model.addAttribute("title", "Volgende training-prompt")
        model.addAttribute("prompt", aiAnalysisService.buildNextTrainingPrompt(resolveNextTrainingParams(
            distanceKm, trainingType, goalRace, raceDate, goalTime, trainingDate, notes,
        )))
        model.addAttribute("backUrl", "/next-training")
        model.addAttribute("downloadUrl", buildNextTrainingQuery(
            "/next-training/prompt/download", distanceKm, trainingType, goalRace, raceDate, goalTime, trainingDate, notes,
        ))
        return "ai"
    }

    @GetMapping("/next-training/prompt/download")
    fun nextTrainingPromptDownload(
        @RequestParam(name = "distanceKm") distanceKm: String? = null,
        @RequestParam(name = "trainingType") trainingType: String? = null,
        @RequestParam(name = "goalRace") goalRace: String? = null,
        @RequestParam(name = "raceDate") raceDate: String? = null,
        @RequestParam(name = "goalTime") goalTime: String? = null,
        @RequestParam(name = "trainingDate") trainingDate: String? = null,
        @RequestParam(name = "notes") notes: String? = null,
    ): ResponseEntity<ByteArray> {
        val bytes = aiAnalysisService.buildNextTrainingPrompt(resolveNextTrainingParams(
            distanceKm, trainingType, goalRace, raceDate, goalTime, trainingDate, notes,
        )).toByteArray()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"volgende-training-prompt.txt\"")
            .contentType(MediaType.TEXT_PLAIN)
            .body(bytes)
    }

    private fun buildNextTrainingQuery(
        path: String,
        distanceKm: String?,
        trainingType: String?,
        goalRace: String?,
        raceDate: String?,
        goalTime: String?,
        trainingDate: String?,
        notes: String?,
    ): String {
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val params = listOfNotNull(
            distanceKm?.takeIf { it.isNotBlank() }?.let { "distanceKm=${enc(it)}" },
            trainingType?.takeIf { it.isNotBlank() }?.let { "trainingType=${enc(it)}" },
            goalRace?.takeIf { it.isNotBlank() }?.let { "goalRace=${enc(it)}" },
            raceDate?.takeIf { it.isNotBlank() }?.let { "raceDate=${enc(it)}" },
            goalTime?.takeIf { it.isNotBlank() }?.let { "goalTime=${enc(it)}" },
            trainingDate?.takeIf { it.isNotBlank() }?.let { "trainingDate=${enc(it)}" },
            notes?.takeIf { it.isNotBlank() }?.let { "notes=${enc(it)}" },
        )
        return if (params.isEmpty()) path else "$path?${params.joinToString("&")}"
    }

    private fun resolveNextTrainingParams(
        distanceKm: String?,
        trainingType: String?,
        goalRace: String?,
        raceDate: String?,
        goalTime: String?,
        trainingDate: String?,
        notes: String?,
    ) = NextTrainingParams(
        distanceKm = distanceKm?.takeIf { it.isNotBlank() }?.replace(",", ".")?.toDoubleOrNull(),
        trainingType = trainingType,
        goalRace = goalRace,
        raceDate = raceDate,
        goalTime = goalTime,
        trainingDate = trainingDate,
        notes = notes,
    )

    @GetMapping("/rate-training")
    fun rateTraining(model: Model): String {
        val runs = activityRepository.findAll()
            .filter { it.type in runTypes }
            .sortedByDescending { it.startDate }
            .take(50)
        model.addAttribute("title", "Training beoordelen")
        model.addAttribute("recentRuns", runs.map { toActivityRow(it) })
        return "rate-training"
    }

    @GetMapping("/rate-training/prompt")
    fun rateTrainingPrompt(model: Model, @RequestParam activityId: Long): String {
        val prompt = aiAnalysisService.buildActivityRatingPrompt(activityId)
            ?: return "redirect:/rate-training"
        model.addAttribute("title", "Training-beoordeling-prompt")
        model.addAttribute("prompt", prompt)
        model.addAttribute("backUrl", "/rate-training")
        model.addAttribute("downloadUrl", "/rate-training/prompt/download?activityId=$activityId")
        return "ai"
    }

    @GetMapping("/rate-training/prompt/download")
    fun rateTrainingPromptDownload(@RequestParam activityId: Long): ResponseEntity<ByteArray> {
        val prompt = aiAnalysisService.buildActivityRatingPrompt(activityId)
            ?: return ResponseEntity.notFound().build()
        val bytes = prompt.toByteArray()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"training-beoordeling-$activityId.txt\"")
            .contentType(MediaType.TEXT_PLAIN)
            .body(bytes)
    }

    @GetMapping("/compare")
    fun compare(
        model: Model,
        @RequestParam fromA: String? = null,
        @RequestParam tillA: String? = null,
        @RequestParam fromB: String? = null,
        @RequestParam tillB: String? = null,
    ): String {
        val (a, b) = resolveComparisonRanges(fromA, tillA, fromB, tillB)

        model.addAttribute("title", "Periodes vergelijken")
        model.addAttribute("fromA", a.from.toString())
        model.addAttribute("tillA", a.till.toString())
        model.addAttribute("fromB", b.from.toString())
        model.addAttribute("tillB", b.till.toString())

        val hasData = activityRepository.findAll().isNotEmpty()
        model.addAttribute("hasData", hasData)
        if (!hasData) return "compare"

        val result = periodComparisonService.compare(a, b, labelA = periodLabel("A", a), labelB = periodLabel("B", b))
        model.addAttribute("result", result)

        model.addAttribute("trendMonths", result.monthlyTrend.map { it.month })
        model.addAttribute("trendEasyEf", result.monthlyTrend.map { it.easyEf })
        model.addAttribute("trendIntervalEf", result.monthlyTrend.map { it.intervalEf })

        return "compare"
    }

    @GetMapping("/compare/prompt")
    fun comparePrompt(
        model: Model,
        @RequestParam fromA: String? = null,
        @RequestParam tillA: String? = null,
        @RequestParam fromB: String? = null,
        @RequestParam tillB: String? = null,
    ): String {
        val (a, b) = resolveComparisonRanges(fromA, tillA, fromB, tillB)
        model.addAttribute("title", "Vergelijk-prompt")
        model.addAttribute("prompt", aiAnalysisService.buildComparisonPrompt(a, b, periodLabel("A", a), periodLabel("B", b)))
        model.addAttribute("downloadUrl", "/compare/prompt/download?fromA=${a.from}&tillA=${a.till}&fromB=${b.from}&tillB=${b.till}")
        model.addAttribute("backUrl", "/compare")
        return "ai"
    }

    @GetMapping("/compare/prompt/download")
    fun comparePromptDownload(
        @RequestParam fromA: String? = null,
        @RequestParam tillA: String? = null,
        @RequestParam fromB: String? = null,
        @RequestParam tillB: String? = null,
    ): ResponseEntity<ByteArray> {
        val (a, b) = resolveComparisonRanges(fromA, tillA, fromB, tillB)
        val bytes = aiAnalysisService.buildComparisonPrompt(a, b, periodLabel("A", a), periodLabel("B", b)).toByteArray()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vergelijk-prompt.txt\"")
            .contentType(MediaType.TEXT_PLAIN)
            .body(bytes)
    }

    private fun periodLabel(name: String, r: DateRange) = "Periode $name (${r.from} t/m ${r.till})"

    /** Both bounds inclusive local dates ("t/m"). Defaults: A = 8 to 6 months ago, B = last 2 months. */
    private fun resolveComparisonRanges(fromA: String?, tillA: String?, fromB: String?, tillB: String?): Pair<DateRange, DateRange> {
        val today = LocalDate.now()
        val a = DateRange(parseDate(fromA) ?: today.minusMonths(8), parseDate(tillA) ?: today.minusMonths(6))
        val b = DateRange(parseDate(fromB) ?: today.minusMonths(2), parseDate(tillB) ?: today)
        return a to b
    }

    private fun jsonStr(value: Any?): String {
        return if (value == null) "null" else "\"${value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
    }

    private fun appendLaps(sb: StringBuilder, laps: List<Lap>, activity: Activity) {
        if (laps.isEmpty()) {
            sb.appendLine("      \"laps\": [],")
            return
        }
        sb.appendLine("      \"laps\": [")
        val labels = lapLabels(laps, activity)
        laps.forEachIndexed { i, lap ->
            sb.appendLine("        {")
            sb.appendLine("          \"lapIndex\": ${lap.lapIndex},")
            sb.appendLine("          \"name\": ${jsonStr(lap.name)},")
            sb.appendLine("          \"distanceM\": ${lap.distance},")
            sb.appendLine("          \"movingTimeS\": ${lap.movingTime},")
            sb.appendLine("          \"averageSpeedMs\": ${lap.averageSpeed},")
            sb.appendLine("          \"averageHeartrate\": ${lap.averageHeartrate ?: "null"},")
            sb.appendLine("          \"maxHeartrate\": ${lap.maxHeartrate ?: "null"},")
            sb.appendLine("          \"averageCadence\": ${lap.averageCadence ?: "null"},")
            sb.appendLine("          \"typeAuto\": ${jsonStr(labels[i])}")
            sb.append("        }")
            if (i < laps.size - 1) sb.appendLine(",") else sb.appendLine()
        }
        sb.appendLine("      ],")
    }

    private fun appendStreams(sb: StringBuilder, streams: ActivityStream?) {
        if (streams == null) {
            sb.appendLine("      \"streams\": null")
            return
        }
        sb.appendLine("      \"streams\": {")
        sb.appendLine("        \"time\": ${toJsonArray(streams.time)},")
        sb.appendLine("        \"heartrate\": ${toJsonArray(streams.heartrate)},")
        sb.appendLine("        \"cadence\": ${toJsonArray(streams.cadence)},")
        sb.appendLine("        \"altitude\": ${toJsonArray(streams.altitude)},")
        sb.appendLine("        \"velocitySmooth\": ${toJsonArray(streams.velocitySmooth)},")
        sb.appendLine("        \"distance\": ${toJsonArray(streams.distance)},")
        sb.appendLine("        \"gradeSmooth\": ${toJsonArray(streams.gradeSmooth)},")
        sb.appendLine("        \"temp\": ${toJsonArray(streams.temp)}")
        sb.appendLine("      }")
    }

    private fun toJsonArray(list: List<*>?): String {
        if (list == null) return "null"
        return list.joinToString(",", "[", "]")
    }

    private data class ActivityRow(
        val id: Long,
        val name: String,
        val date: String,
        val type: String,
        val typeClass: String,
        val distance: String,
        val pace: String,
        val avgHr: String,
        val maxHr: String?,
        val cadence: String?,
        val elevation: String?,
        val duration: String,
        val sufferScore: String?,
    )

    private fun toActivityRow(a: Activity) = ActivityRow(
        id = a.id,
        name = a.name,
        date = ActivityTime.local(a).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
        type = displayType(a.type),
        typeClass = typeClass(a.type),
        distance = "%.2f km".format(a.distance / 1000),
        pace = Format.pace(a.averageSpeed.toDouble()),
        avgHr = Format.hr(a.averageHeartrate, unit = false),
        maxHr = a.maxHeartrate?.let { Format.hr(it, unit = false) },
        cadence = Cadence.toSpm(a.averageCadence, a.type)?.let { "%.0f".format(it) },
        elevation = "%.0f m".format(a.totalElevationGain),
        duration = Format.duration(a.movingTime),
        sufferScore = a.sufferScore?.toString(),
    )

    private fun displayType(type: String): String = when (type) {
        "Run" -> "Loop"
        "TrailRun" -> "Trail"
        "VirtualRun" -> "Virtueel"
        "Ride" -> "Fiets"
        "VirtualRide" -> "Virtueel"
        "Swim" -> "Zwem"
        else -> type
    }

    private fun typeClass(type: String): String = when (type) {
        "Run", "TrailRun", "VirtualRun" -> "run"
        "Ride", "VirtualRide" -> "ride"
        "Swim" -> "swim"
        else -> "other"
    }

    private fun addFilterAttributes(model: Model, period: String?, from: String?, till: String?, type: String?) {
        model.addAttribute("filterPeriod", period ?: "")
        model.addAttribute("filterFrom", from ?: "")
        model.addAttribute("filterTill", till ?: "")
        model.addAttribute("filterType", type ?: "")
    }

    private fun filterActivities(
        activities: List<Activity>,
        period: String?,
        from: String?,
        till: String?,
        type: String?,
    ): List<Activity> {
        var result = activities
        val effectiveType = type?.takeIf { it.isNotBlank() } ?: "all"
        result = when (effectiveType) {
            "all" -> result
            "run" -> result.filter { it.type in runTypes }
            "ride" -> result.filter { it.type in listOf("Ride", "VirtualRide") }
            "swim" -> result.filter { it.type == "Swim" }
            "other" -> result.filter { it.type !in listOf("Run", "TrailRun", "VirtualRun", "Ride", "VirtualRide", "Swim") }
            else -> result
        }
        val range = periodRange(period, from, till)
        return result.filter { range.contains(it) }
    }

    /** Main filter presets -> inclusive range of activity-local dates. */
    private fun periodRange(period: String?, from: String?, till: String?): DateRange {
        val today = LocalDate.now()
        return when (period?.takeIf { it.isNotBlank() }) {
            "last-month" -> DateRange(today.minusMonths(1), today)
            "last-year" -> DateRange(today.minusYears(1), today)
            "ytd" -> DateRange(today.withDayOfYear(1), today)
            "last-2-years" -> DateRange(today.minusYears(2), today)
            "last-3-years" -> DateRange(today.minusYears(3), today)
            "custom" -> DateRange(parseDate(from), parseDate(till))
            else -> DateRange.ALL
        }
    }

    /** Chart presets (weekly volume / evolution) -> inclusive range of activity-local dates. */
    private fun presetRange(preset: String?, from: String?, till: String?): DateRange {
        val today = LocalDate.now()
        return when (preset?.takeIf { it.isNotBlank() }) {
            "3m" -> DateRange(today.minusMonths(3), today)
            "6m" -> DateRange(today.minusMonths(6), today)
            "ytd" -> DateRange(today.withDayOfYear(1), today)
            "1y" -> DateRange(today.minusYears(1), today)
            "2y" -> DateRange(today.minusYears(2), today)
            "3y" -> DateRange(today.minusYears(3), today)
            "custom" -> DateRange(parseDate(from), parseDate(till))
            else -> DateRange.ALL
        }
    }

    private fun parseDate(v: String?): LocalDate? =
        v?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun filterByDistanceAndDuration(
        activities: List<Activity>,
        distanceMin: String?,
        distanceMax: String?,
        durationMin: String?,
        durationMax: String?,
    ): List<Activity> {
        var result = activities
        val distMin = distanceMin?.takeIf { it.isNotBlank() }?.toFloatOrNull()?.let { it * 1000 }
        val distMax = distanceMax?.takeIf { it.isNotBlank() }?.toFloatOrNull()?.let { it * 1000 }
        val durMin = durationMin?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let { it * 60 }
        val durMax = durationMax?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let { it * 60 }
        if (distMin != null) result = result.filter { it.distance >= distMin }
        if (distMax != null) result = result.filter { it.distance <= distMax }
        if (durMin != null) result = result.filter { it.movingTime >= durMin }
        if (durMax != null) result = result.filter { it.movingTime <= durMax }
        return result
    }

    companion object {
        val runTypes = listOf("Run", "TrailRun", "VirtualRun")
    }
}
