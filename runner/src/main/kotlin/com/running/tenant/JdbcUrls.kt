package com.running.tenant

object JdbcUrls {
    private val POSTGRES = Regex("^(jdbc:postgresql://[^/]+/)([^?;]*)(.*)$")

    fun isPostgres(url: String): Boolean = POSTGRES.matches(url)

    fun databaseName(url: String): String? = POSTGRES.matchEntire(url)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }

    fun withDatabase(url: String, database: String): String {
        val match = requireNotNull(POSTGRES.matchEntire(url)) { "Not a PostgreSQL JDBC URL" }
        return match.groupValues[1] + database + match.groupValues[3]
    }
}
