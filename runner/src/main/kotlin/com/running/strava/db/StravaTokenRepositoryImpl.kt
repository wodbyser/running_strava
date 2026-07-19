package com.running.strava.db

import com.running.strava.domain.StravaToken
import com.running.strava.spi.StravaTokenRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

interface StravaTokenJpaRepository : JpaRepository<StravaTokenEntity, Long>

@Repository
class StravaTokenRepositoryImpl(
    private val jpaRepository: StravaTokenJpaRepository,
) : StravaTokenRepository {

    override fun save(token: StravaToken) {
        val entity = jpaRepository.findById(1).orElse(StravaTokenEntity())
        entity.accessToken = token.accessToken
        entity.refreshToken = token.refreshToken
        entity.expiresAt = token.expiresAt
        entity.athleteId = token.athleteId
        jpaRepository.save(entity)
    }

    override fun get(): StravaToken? {
        return jpaRepository.findById(1).map { entity ->
            StravaToken(
                accessToken = entity.accessToken,
                refreshToken = entity.refreshToken,
                expiresAt = entity.expiresAt,
                athleteId = entity.athleteId,
            )
        }.orElse(null)
    }

    override fun delete() {
        jpaRepository.deleteById(1)
    }

    fun getRestingHr(): Int? {
        return jpaRepository.findById(1).map { it.restingHr }.orElse(null)
    }

    fun saveRestingHr(rhr: Int) {
        val entity = jpaRepository.findById(1).orElse(StravaTokenEntity())
        entity.restingHr = rhr
        jpaRepository.save(entity)
    }
}
