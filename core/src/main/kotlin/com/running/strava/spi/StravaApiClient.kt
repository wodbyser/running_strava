package com.running.strava.spi

import com.running.strava.domain.Activity
import com.running.strava.domain.ActivityStream
import com.running.strava.domain.StravaToken

interface StravaApiClient {
    fun exchangeToken(code: String): StravaToken
    fun refreshToken(refreshToken: String): StravaToken
    fun getAthleteActivities(token: StravaToken, page: Int, perPage: Int, after: Long? = null): List<Activity>
    fun getActivity(token: StravaToken, activityId: Long): Activity
    fun getActivityStreams(token: StravaToken, activityId: Long): ActivityStream
}
