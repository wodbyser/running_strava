package com.running.analysis

import com.running.strava.domain.ActivityStream
import com.running.strava.spi.ActivityRepository
import com.running.tenant.TenantContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** The same Strava activity id in two tenants must give two cache entries (design §10.3). */
class TenantCacheTest {
    private val repo = mock(ActivityRepository::class.java)
    private val service = BestEffortService(repo)

    private fun stream(secondsPerKm: Int): ActivityStream {
        val distance = (0..6000 step 10).map { it.toFloat() }
        val time = distance.map { (it / 1000f * secondsPerKm).toInt() }
        return ActivityStream(time, distance, null, null, null, null, null, null, null)
    }

    init {
        `when`(repo.findStreams(1L)).thenAnswer { if (TenantContext.current() == "anna") stream(300) else stream(240) }
    }

    @Test
    fun `same activity id under two tenants gives two results`() {
        val owner = TenantContext.asTenant("wout") { service.streamEfforts(1L) }
        val anna = TenantContext.asTenant("anna") { service.streamEfforts(1L) }
        assertEquals(true, owner.isNotEmpty())
        assertEquals(true, owner.keys.all { owner.getValue(it) < anna.getValue(it) })
        TenantContext.asTenant("wout") { service.streamEfforts(1L) }
        TenantContext.asTenant("anna") { service.streamEfforts(1L) }
        verify(repo, times(2)).findStreams(1L)
    }

    @Test
    fun `evict forgets one tenant only`() {
        TenantContext.asTenant("wout") { service.streamEfforts(1L) }
        TenantContext.asTenant("anna") { service.streamEfforts(1L) }
        service.evict("anna")
        TenantContext.asTenant("wout") { service.streamEfforts(1L) }
        verify(repo, times(2)).findStreams(1L)
        TenantContext.asTenant("anna") { service.streamEfforts(1L) }
        verify(repo, times(3)).findStreams(1L)
    }
}
