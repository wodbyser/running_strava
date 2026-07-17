package com.running.strava.domain

data class ActivityStream(
    val time: List<Int>?,
    val distance: List<Float>?,
    val latlng: List<List<Double>>?,
    val altitude: List<Float>?,
    val heartrate: List<Int>?,
    val cadence: List<Int>?,
    val velocitySmooth: List<Float>?,
    val gradeSmooth: List<Float>?,
    val temp: List<Int>?,
)
