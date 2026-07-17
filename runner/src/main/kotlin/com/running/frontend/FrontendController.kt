package com.running.frontend

import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.spi.ActivityRepository
import com.running.strava.spi.StravaTokenRepository
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
) {

    @GetMapping("/")
    fun dashboard(
        model: Model,
        @RequestParam period: String? = null,
        @RequestParam from: String? = null,
        @RequestParam till: String? = null,
        @RequestParam type: String? = "run",
        @RequestParam(name = "weekly") weeklyPeriod: String? = null,
        @RequestParam(name = "weeklyFrom") weeklyFrom: String? = null,
        @RequestParam(name = "weeklyTill") weeklyTill: String? = null,
    ): String {
        val allActivities = activityRepository.findAll()
        val filtered = filterActivities(allActivities, period, from, till, type)
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
        addFilterAttributes(model, period, from, till, type)

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
    ): String {
        val allActivities = activityRepository.findAll()
        val filtered = filterActivities(allActivities, period, from, till, type)

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
        return "activity"
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

    private fun jsonStr(value: Any?): String {
        return if (value == null) "null" else "\"${value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
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

    companion object {
        val runTypes = listOf("Run", "TrailRun", "VirtualRun")
    }
}
