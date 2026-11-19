package com.running.tenant

import org.hibernate.boot.Metadata
import org.hibernate.boot.spi.BootstrapContext
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.integrator.spi.Integrator
import org.hibernate.service.ServiceRegistry
import org.hibernate.engine.config.spi.ConfigurationService
import org.hibernate.service.spi.SessionFactoryServiceRegistry
import org.hibernate.tool.schema.TargetType
import org.hibernate.tool.schema.spi.ContributableMatcher
import org.hibernate.tool.schema.spi.ExceptionHandler
import org.hibernate.tool.schema.spi.ExecutionOptions
import org.hibernate.tool.schema.spi.SchemaManagementTool
import org.hibernate.tool.schema.spi.ScriptTargetOutput
import org.hibernate.tool.schema.spi.TargetDescriptor
import org.slf4j.LoggerFactory
import java.util.EnumSet
import javax.sql.DataSource

class SchemaGuardException(message: String) : RuntimeException(message)

/** Only additive DDL may ever reach a database (design §8.2). */
object SchemaGuard {
    private val ALLOWED = listOf(
        Regex("^create table .*", RegexOption.IGNORE_CASE),
        Regex("^create (unique )?index .*", RegexOption.IGNORE_CASE),
        Regex("^alter table (if exists )?\\S+ add (column|constraint) .*", RegexOption.IGNORE_CASE),
    )

    fun offending(statements: List<String>): List<String> = statements.filterNot { s -> ALLOWED.any { it.matches(s.trim().replace(Regex("\\s+"), " ")) } }
}

/**
 * Hibernate drops and re-adds every unique constraint on each run (`unique_constraint_strategy`, default
 * DROP_RECREATE_QUIETLY). That churn is not additive, so it is removed from the plan: the quiet drops go, and an
 * add for a constraint that already exists goes too. A constraint on a new table keeps its add.
 */
object UniqueConstraintChurn {
    private val DROP = Regex("^alter table (?:if exists )?\\S+ drop constraint if exists \\S+$", RegexOption.IGNORE_CASE)
    private val ADD_UNIQUE = Regex("^alter table (?:if exists )?\\S+ add constraint (\\S+) unique .*", RegexOption.IGNORE_CASE)

    fun strip(statements: List<String>, existingConstraints: Set<String>): List<String> {
        val existing = existingConstraints.map { it.lowercase() }.toSet()
        return statements.filterNot { s ->
            val one = s.trim().replace(Regex("\\s+"), " ")
            DROP.matches(one) || ADD_UNIQUE.matchEntire(one)?.groupValues?.get(1)?.lowercase()?.let { it in existing } == true
        }
    }
}

/** Captures Hibernate's metadata while the single EntityManagerFactory boots (design §8.2); no second factory. */
class HibernateMetadataCapture : Integrator {
    @Volatile
    var metadata: Metadata? = null
        private set

    @Volatile
    var registry: ServiceRegistry? = null
        private set

    override fun integrate(metadata: Metadata, bootstrapContext: BootstrapContext, sessionFactory: SessionFactoryImplementor) {
        this.metadata = metadata
        this.registry = sessionFactory.serviceRegistry
    }

    override fun disintegrate(sessionFactory: SessionFactoryImplementor, serviceRegistry: SessionFactoryServiceRegistry) = Unit

    /** The statements Hibernate would run against the database of the current tenant. Nothing is executed (script target only). */
    fun dryRun(): List<String> {
        val md = checkNotNull(metadata) { "Hibernate metadata not captured yet" }
        val reg = checkNotNull(registry)
        val settings = reg.requireService(ConfigurationService::class.java).settings.toMutableMap<String, Any>()
        val statements = mutableListOf<String>()
        val target = object : TargetDescriptor {
            override fun getTargetTypes(): EnumSet<TargetType> = EnumSet.of(TargetType.SCRIPT)
            override fun getScriptTargetOutput() = object : ScriptTargetOutput {
                override fun prepare() = Unit
                override fun accept(command: String) { statements += command.trim().removeSuffix(";").trim() }
                override fun release() = Unit
            }
        }
        val options = object : ExecutionOptions {
            override fun getConfigurationValues(): MutableMap<String, Any> = settings
            override fun shouldManageNamespaces() = false
            override fun getExceptionHandler() = ExceptionHandler { throw it }
        }
        reg.requireService(SchemaManagementTool::class.java).getSchemaMigrator(settings).doMigration(md, options, ContributableMatcher.ALL, target)
        return statements.filter { it.isNotEmpty() }
    }
}

/** Dry run, guard, then run exactly the vetted statements, inside the tenant that is currently bound. */
class TenantSchemaUpdater(
    private val plan: () -> List<String>,
    private val run: (String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(TenantSchemaUpdater::class.java)

    /** Returns the statements that were executed. Throws [SchemaGuardException] without executing anything when one is not additive. */
    fun update(): List<String> {
        val tenant = TenantContext.currentOrFail()
        val statements = plan()
        val bad = SchemaGuard.offending(statements)
        if (bad.isNotEmpty()) {
            log.error("Schema update for tenant '{}' refused, non-additive statements: {}", tenant, bad)
            throw SchemaGuardException("Schema update for tenant '$tenant' contains ${bad.size} non-additive statement(s)")
        }
        statements.forEach { log.info("Schema update '{}': {}", tenant, it) }
        statements.forEach(run)
        log.info("Schema update '{}': {} statement(s)", tenant, statements.size)
        return statements
    }

    companion object {
        fun forHibernate(capture: HibernateMetadataCapture, dataSource: DataSource) = TenantSchemaUpdater(
            { UniqueConstraintChurn.strip(capture.dryRun(), uniqueConstraints(dataSource)) },
        ) { sql -> dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } } }

        private fun uniqueConstraints(dataSource: DataSource): Set<String> = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT conname FROM pg_constraint WHERE contype = 'u' AND connamespace = 'public'::regnamespace").use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
                }
            }
        }
    }
}
