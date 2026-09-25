package com.running.frontend

import com.running.analysis.AiAnalysisService
import com.running.analysis.NextTrainingParams
import com.running.analysis.PeriodComparisonService
import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
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
import java.time.DayOfWeek
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

        val totalDistance = if (runs.isNotEmpty()) runs.sumOf { it.distance.toDouble() } else 0.0
        val totalTime = if (runs.isNotEmpty()) runs.sumOf { it.movingTime.toLong() } else 0L
        val avgHr = if (runs.isNotEmpty()) runs.mapNotNull { it.averageHeartrate }.average() else 0.0
        val avgCadence = if (runs.isNotEmpty()) runs.mapNotNull { it.averageCadence }.average() else 0.0
        val avgSpeed = if (runs.isNotEmpty()) runs.mapNotNull { it.averageSpeed }.average() else 0.0
        val recentActivities = sorted.take(10).map { toActivityRow(it) }
        val pbs = calculatePBs(runs)
        val lastSync = syncStatus.lastSyncAt?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) ?: "nooit"

        val now = ZonedDateTime.now()
        var weeklySince: ZonedDateTime? = null
        var weeklyUntil: ZonedDateTime? = null
        when (weeklyPeriod?.takeIf { it.isNotBlank() }) {
            "3m" -> weeklySince = now.minusMonths(3)
            "6m" -> weeklySince = now.minusMonths(6)
            "ytd" -> weeklySince = now.withDayOfYear(1)
            "1y" -> weeklySince = now.minusYears(1)
            "2y" -> weeklySince = now.minusYears(2)
            "3y" -> weeklySince = now.minusYears(3)
            "custom" -> {
                weeklySince = weeklyFrom?.takeIf { it.isNotBlank() }?.let {
                    LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault())
                }
                weeklyUntil = weeklyTill?.takeIf { it.isNotBlank() }?.let {
                    LocalDate.parse(it).plusDays(1).atStartOfDay(ZoneId.systemDefault())
                }
            }
        }
        val allRuns = allActivities.filter { it.type in runTypes }
        val weeklyRuns = allRuns.filter { a ->
            (weeklySince == null || !a.startDate.isBefore(weeklySince)) &&
            (weeklyUntil == null || !a.startDate.isAfter(weeklyUntil))
        }

        val weekRangeStart = weeklySince ?: (allRuns.minOfOrNull { it.startDate } ?: now)
        val weekRangeEnd = weeklyUntil ?: now
        val weeklyVolume = mutableMapOf<LocalDate, Double>()
        var weekCursor = weekRangeStart.toLocalDate().with(DayOfWeek.MONDAY)
        val weekEnd = weekRangeEnd.toLocalDate().with(DayOfWeek.MONDAY)
        while (!weekCursor.isAfter(weekEnd)) {
            weeklyVolume[weekCursor] = 0.0
            weekCursor = weekCursor.plusWeeks(1)
        }
        weeklyRuns.forEach { a ->
            val w = a.startDate.toLocalDate().with(DayOfWeek.MONDAY)
            weeklyVolume[w] = (weeklyVolume[w] ?: 0.0) + a.distance.toDouble() / 1000
        }
        val sortedVolume = weeklyVolume.toSortedMap().entries.toList()
        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM")
        model.addAttribute("weeklyLabels", sortedVolume.map { it.key.format(dateFormatter) })
        model.addAttribute("weeklyDistances", sortedVolume.map { "%.1f".format(it.value).toDouble() })
        model.addAttribute("weeklyWeekStarts", sortedVolume.map { it.key.toString() })

        var evolutionSince: ZonedDateTime? = null
        var evolutionUntil: ZonedDateTime? = null
        when (evolutionPeriod?.takeIf { it.isNotBlank() }) {
            "3m" -> evolutionSince = now.minusMonths(3)
            "6m" -> evolutionSince = now.minusMonths(6)
            "ytd" -> evolutionSince = now.withDayOfYear(1)
            "1y" -> evolutionSince = now.minusYears(1)
            "2y" -> evolutionSince = now.minusYears(2)
            "3y" -> evolutionSince = now.minusYears(3)
            "custom" -> {
                evolutionSince = evolutionFrom?.takeIf { it.isNotBlank() }?.let {
                    LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault())
                }
                evolutionUntil = evolutionTill?.takeIf { it.isNotBlank() }?.let {
                    LocalDate.parse(it).plusDays(1).atStartOfDay(ZoneId.systemDefault())
                }
            }
        }
        val evolutionRuns = allRuns.filter { a ->
            (evolutionSince == null || !a.startDate.isBefore(evolutionSince)) &&
            (evolutionUntil == null || !a.startDate.isAfter(evolutionUntil))
        }
        val monthlyTrend = periodComparisonService.buildMonthlyTrend(evolutionRuns)
        model.addAttribute("evolutionMonths", monthlyTrend.map { it.month })
        model.addAttribute("evolutionEasyEf", monthlyTrend.map { it.easyEf })
        model.addAttribute("evolutionIntervalEf", monthlyTrend.map { it.intervalEf })
        model.addAttribute("evolutionEasyRunCount", monthlyTrend.map { it.easyRunCount })
        model.addAttribute("evolutionIntervalRepCount", monthlyTrend.map { it.intervalRepCount })
        model.addAttribute("filterEvolution", evolutionPeriod ?: "all")
        model.addAttribute("filterEvolutionFrom", evolutionFrom ?: "")
        model.addAttribute("filterEvolutionTill", evolutionTill ?: "")

        model.addAttribute("hasToken", hasToken)
        model.addAttribute("hasData", runs.isNotEmpty())
        model.addAttribute("stats", mapOf<String, Any>(
            "totalRuns" to runs.size,
            "totalDistance" to "%.1f".format(totalDistance / 1000),
            "totalTime" to totalTime / 3600,
            "avgPace" to calculatePace(avgSpeed),
            "avgHeartrate" to "%.0f".format(avgHr),
            "avgCadence" to "%.0f".format(avgCadence),
            "lastSync" to lastSync,
        ))
        model.addAttribute("recent", recentActivities)
        model.addAttribute("pbs", pbs)
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
        detail["date"] = a.startDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        detail["type"] = a.type
        detail["typeClass"] = typeClass(a.type)
        detail["distance"] = "%.2f km".format(a.distance / 1000)
        detail["pace"] = calculatePace(a.averageSpeed?.toDouble() ?: 0.0)
        detail["duration"] = formatDuration(a.movingTime)
        detail["avgHr"] = a.averageHeartrate?.let { "%.0f bpm".format(it) } ?: "-"
        detail["maxHr"] = a.maxHeartrate?.let { "%.0f bpm".format(it) } ?: "-"
        detail["cadence"] = a.averageCadence?.let { "%.0f spm".format(it) } ?: "-"
        detail["elevation"] = "%.0f m".format(a.totalElevationGain)
        detail["maxSpeed"] = calculatePace((a.maxSpeed * 3.6).toDouble())
        detail["avgWatts"] = a.averageWatts?.let { "%.0f W".format(it) } ?: "-"
        detail["maxWatts"] = a.maxWatts?.let { "%.0f W".format(it) } ?: "-"
        detail["calories"] = a.calories?.let { "%.0f".format(it) } ?: "-"
        detail["sufferScore"] = a.sufferScore?.toString() ?: "-"
        detail["description"] = a.description?.take(500) ?: "-"
        detail["stravaUrl"] = "https://www.strava.com/activities/${a.id}"
        model.addAttribute("a", detail)
        model.addAttribute("title", a.name)

        val laps = activityRepository.findLaps(a.id)
        model.addAttribute("laps", buildLapRows(laps))
        model.addAttribute("hasLaps", laps.size > 1)

        return "activity"
    }

    @GetMapping("/activity/{id}/export")
    fun exportActivity(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val activity = activityRepository.findById(id)
            ?: return ResponseEntity.notFound().build()
        val laps = activityRepository.findLaps(id)

        val csv = buildString {
            appendLine("lap,distance_m,duration_s,pace_min_per_km,avg_hr,max_hr,cadence,type")
            laps.forEach { lap ->
                val paceSecPerKm = if (lap.averageSpeed > 0) (1000 / lap.averageSpeed) else 0f
                appendLine(listOf(
                    lap.lapIndex,
                    "%.0f".format(lap.distance),
                    lap.movingTime,
                    "%.0f".format(paceSecPerKm),
                    lap.averageHeartrate?.let { "%.0f".format(it) } ?: "",
                    lap.maxHeartrate?.let { "%.0f".format(it) } ?: "",
                    lap.averageCadence?.let { "%.0f".format(it) } ?: "",
                    classifyLap(lap, laps),
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

    private fun buildLapRows(laps: List<com.running.strava.domain.Lap>): List<LapRow> {
        val kinds = com.running.strava.domain.LapClassifier.classify(laps)
        return laps.zip(kinds).map { (lap, kind) ->
            LapRow(
                index = lap.lapIndex,
                distance = "%.0f m".format(lap.distance),
                duration = formatDuration(lap.movingTime),
                pace = calculatePace(lap.averageSpeed.toDouble()),
                avgHr = lap.averageHeartrate?.let { "%.0f".format(it) } ?: "-",
                maxHr = lap.maxHeartrate?.let { "%.0f".format(it) } ?: "-",
                cadence = lap.averageCadence?.let { "%.0f".format(it) } ?: "-",
                type = if (laps.size < 2) "-" else lapKindLabel(kind),
            )
        }
    }

    private fun lapKindLabel(kind: com.running.strava.domain.LapKind): String = when (kind) {
        com.running.strava.domain.LapKind.REP -> "interval"
        com.running.strava.domain.LapKind.FLOAT -> "float/matig"
        com.running.strava.domain.LapKind.EASY -> "rust/herstel"
        com.running.strava.domain.LapKind.NOISE -> "ruis"
    }

    private fun classifyLap(lap: com.running.strava.domain.Lap, laps: List<com.running.strava.domain.Lap>): String {
        if (laps.size < 2) return "-"
        val kinds = com.running.strava.domain.LapClassifier.classify(laps)
        val idx = laps.indexOf(lap)
        if (idx < 0) return "-"
        return lapKindLabel(kinds[idx])
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
        model.addAttribute("pbs", calculatePBs(runs))
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

    @GetMapping("/sync")
    fun syncGet(ra: RedirectAttributes): String {
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

    @GetMapping("/fetch-all")
    fun fetchAllGet(ra: RedirectAttributes): String {
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

    @GetMapping("/fetch-remaining")
    fun fetchRemainingGet(ra: RedirectAttributes): String {
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

    @GetMapping("/backfill-laps")
    fun backfillLapsGet(ra: RedirectAttributes): String {
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
                val paceSeconds = if (a.averageSpeed > 0) (1000 / a.averageSpeed).toInt() else 0
                val pace = if (paceSeconds > 0) "${paceSeconds / 60}:${(paceSeconds % 60).toString().padStart(2, '0')}" else null
                appendLine("    {")
                appendLine("      \"id\": ${a.id},")
                appendLine("      \"name\": ${jsonStr(a.name)},")
                appendLine("      \"startDate\": ${jsonStr(a.startDate.toString())},")
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
                appendLaps(this, activityRepository.findLaps(a.id))
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
        val now = ZonedDateTime.now()
        val zone = ZoneId.systemDefault()

        val defaultTillB = now
        val defaultFromB = now.minusMonths(2)
        val defaultTillA = now.minusMonths(6)
        val defaultFromA = now.minusMonths(8)

        val parsedFromA = parseDateParam(fromA, zone) ?: defaultFromA
        val parsedTillA = parseDateParam(tillA, zone, endOfDay = true) ?: defaultTillA
        val parsedFromB = parseDateParam(fromB, zone) ?: defaultFromB
        val parsedTillB = parseDateParam(tillB, zone, endOfDay = true) ?: defaultTillB

        model.addAttribute("title", "Periodes vergelijken")
        model.addAttribute("fromA", parsedFromA.toLocalDate().toString())
        model.addAttribute("tillA", parsedTillA.toLocalDate().toString())
        model.addAttribute("fromB", parsedFromB.toLocalDate().toString())
        model.addAttribute("tillB", parsedTillB.toLocalDate().toString())

        val hasData = activityRepository.findAll().isNotEmpty()
        model.addAttribute("hasData", hasData)
        if (!hasData) return "compare"

        val result = periodComparisonService.compare(
            fromA = parsedFromA,
            tillA = parsedTillA,
            fromB = parsedFromB,
            tillB = parsedTillB,
            labelA = "Periode A (${parsedFromA.toLocalDate()} t/m ${parsedTillA.toLocalDate()})",
            labelB = "Periode B (${parsedFromB.toLocalDate()} t/m ${parsedTillB.toLocalDate()})",
        )
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
        val (a1, a2, b1, b2) = resolveComparisonDates(fromA, tillA, fromB, tillB)
        model.addAttribute("title", "Vergelijk-prompt")
        model.addAttribute("prompt", aiAnalysisService.buildComparisonPrompt(
            a1, a2, b1, b2,
            labelA = "Periode A (${a1.toLocalDate()} t/m ${a2.toLocalDate()})",
            labelB = "Periode B (${b1.toLocalDate()} t/m ${b2.toLocalDate()})",
        ))
        model.addAttribute("downloadUrl", "/compare/prompt/download?fromA=${a1.toLocalDate()}&tillA=${a2.toLocalDate()}&fromB=${b1.toLocalDate()}&tillB=${b2.toLocalDate()}")
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
        val (a1, a2, b1, b2) = resolveComparisonDates(fromA, tillA, fromB, tillB)
        val bytes = aiAnalysisService.buildComparisonPrompt(
            a1, a2, b1, b2,
            labelA = "Periode A (${a1.toLocalDate()} t/m ${a2.toLocalDate()})",
            labelB = "Periode B (${b1.toLocalDate()} t/m ${b2.toLocalDate()})",
        ).toByteArray()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vergelijk-prompt.txt\"")
            .contentType(MediaType.TEXT_PLAIN)
            .body(bytes)
    }

    private data class ComparisonDates(val fromA: ZonedDateTime, val tillA: ZonedDateTime, val fromB: ZonedDateTime, val tillB: ZonedDateTime)

    private fun resolveComparisonDates(fromA: String?, tillA: String?, fromB: String?, tillB: String?): ComparisonDates {
        val now = ZonedDateTime.now()
        val zone = ZoneId.systemDefault()
        val a1 = parseDateParam(fromA, zone) ?: now.minusMonths(8)
        val a2 = parseDateParam(tillA, zone, endOfDay = true) ?: now.minusMonths(6)
        val b1 = parseDateParam(fromB, zone) ?: now.minusMonths(2)
        val b2 = parseDateParam(tillB, zone, endOfDay = true) ?: now
        return ComparisonDates(a1, a2, b1, b2)
    }

    private fun parseDateParam(value: String?, zone: ZoneId, endOfDay: Boolean = false): ZonedDateTime? {
        val v = value?.takeIf { it.isNotBlank() } ?: return null
        val date = LocalDate.parse(v)
        return if (endOfDay) date.plusDays(1).atStartOfDay(zone) else date.atStartOfDay(zone)
    }

    private fun jsonStr(value: Any?): String {
        return if (value == null) "null" else "\"${value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
    }

    private fun appendLaps(sb: StringBuilder, laps: List<com.running.strava.domain.Lap>) {
        if (laps.isEmpty()) {
            sb.appendLine("      \"laps\": [],")
            return
        }
        sb.appendLine("      \"laps\": [")
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
            sb.appendLine("          \"type\": ${jsonStr(classifyLap(lap, laps))}")
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

    private data class PB(
        val label: String,
        val time: String,
        val pace: String,
        val date: String,
        val name: String,
        val activityId: Long?,
    )

    private fun calculatePBs(runs: List<Activity>): List<PB> {
        val distances = mapOf(
            "1 km" to 1000f,
            "5 km" to 5000f,
            "10 km" to 10000f,
            "Halve marathon" to 21097f,
            "Marathon" to 42195f,
        )

        return distances.map { (label, dist) ->
            val best = runs
                .filter { it.distance >= dist * 0.95 && it.distance <= dist * 1.05 }
                .filter { it.averageSpeed > 0 }
                .minByOrNull { it.movingTime.toFloat() / it.distance }

            if (best != null) {
                val paceSeconds = (1000 / best.averageSpeed).toInt()
                val pace = "${paceSeconds / 60}:${(paceSeconds % 60).toString().padStart(2, '0')} /km"
                PB(
                    label = label,
                    time = formatDuration(best.movingTime),
                    pace = pace,
                    date = best.startDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    name = best.name,
                    activityId = best.id,
                )
            } else {
                PB(label = label, time = "-", pace = "-", date = "-", name = "-", activityId = null)
            }
        }
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
        date = a.startDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
        type = displayType(a.type),
        typeClass = typeClass(a.type),
        distance = "%.2f km".format(a.distance / 1000),
        pace = calculatePace(a.averageSpeed?.toDouble() ?: 0.0),
        avgHr = a.averageHeartrate?.let { "%.0f".format(it) } ?: "-",
        maxHr = a.maxHeartrate?.let { "%.0f".format(it) },
        cadence = a.averageCadence?.let { "%.0f".format(it) },
        elevation = "%.0f m".format(a.totalElevationGain),
        duration = formatDuration(a.movingTime),
        sufferScore = a.sufferScore?.toString(),
    )

    private fun calculatePace(speedMs: Double): String {
        if (speedMs <= 0) return "-"
        val paceSeconds = (1000 / speedMs).toInt()
        return "${paceSeconds / 60}:${(paceSeconds % 60).toString().padStart(2, '0')} /km"
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "${h}u${m}m" else "${m}m${s}s"
    }

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
        val effectivePeriod = period?.takeIf { it.isNotBlank() }
        if (effectivePeriod == null || effectivePeriod == "all") return result
        val now = ZonedDateTime.now()
        val (fromDate, tillDate) = when (effectivePeriod) {
            "last-month" -> now.minusMonths(1) to now
            "last-year" -> now.minusYears(1) to now
            "ytd" -> now.withDayOfYear(1) to now
            "last-2-years" -> now.minusYears(2) to now
            "last-3-years" -> now.minusYears(3) to now
            "custom" -> {
                val f = from?.takeIf { it.isNotBlank() }?.let {
                    LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault())
                }
                val t = till?.takeIf { it.isNotBlank() }?.let {
                    LocalDate.parse(it).plusDays(1).atStartOfDay(ZoneId.systemDefault())
                }
                f to t
            }
            else -> null to null
        }
        return result.filter { a ->
            (fromDate == null || !a.startDate.isBefore(fromDate)) &&
            (tillDate == null || !a.startDate.isAfter(tillDate))
        }
    }

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
