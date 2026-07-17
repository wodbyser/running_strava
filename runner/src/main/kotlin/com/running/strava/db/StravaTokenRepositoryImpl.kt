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
        jpaRepository.save(StravaTokenEntity(
            id = 1,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAt = token.expiresAt,
            athleteId = token.athleteId,
        ))
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
}
