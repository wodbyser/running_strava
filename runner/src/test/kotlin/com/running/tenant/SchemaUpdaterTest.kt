package com.running.tenant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaUpdaterTest {
    @Test
    fun `additive statements pass the guard`() {
        val ok = listOf(
            "create table x (id bigint not null, primary key (id))",
            "create index idx on x (id)",
            "create unique index idx2 on x (id)",
            "alter table if exists x add column y varchar(16)",
            "alter table x add constraint fk foreign key (id) references z",
        )
        assertTrue(SchemaGuard.offending(ok).isEmpty())
    }

    @Test
    fun `drop, alter column, rename, truncate and delete are offending`() {
        val bad = listOf("drop table x", "alter table x alter column y type text", "alter table x rename to z", "truncate table x", "delete from x", "alter table x drop column y")
        assertEquals(bad, SchemaGuard.offending(bad))
    }

    @Test
    fun `a refused plan executes nothing`() {
        val executed = mutableListOf<String>()
        val updater = TenantSchemaUpdater({ listOf("create table a (id int)", "alter table a alter column id type bigint") }) { executed += it }
        assertThrows(SchemaGuardException::class.java) { TenantContext.asTenant("wout") { updater.update() } }
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `an additive plan is executed statement by statement, an empty plan runs nothing`() {
        val executed = mutableListOf<String>()
        val plan = listOf("create table a (id int)", "create index i on a (id)")
        assertEquals(plan, TenantContext.asTenant("wout") { TenantSchemaUpdater({ plan }) { executed += it }.update() })
        assertEquals(plan, executed)
        executed.clear()
        assertTrue(TenantContext.asTenant("wout") { TenantSchemaUpdater({ emptyList() }) { executed += it }.update() }.isEmpty())
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `update needs a bound tenant`() {
        assertThrows(TenantNotResolvedException::class.java) { TenantSchemaUpdater({ emptyList() }) { }.update() }
    }

    @Test
    fun `unique constraint churn is removed, a new constraint stays`() {
        val plan = listOf(
            "alter table if exists a drop constraint if exists UKabc",
            "alter table if exists a add constraint UKabc unique (x)",
            "alter table if exists b drop constraint if exists uq_b",
            "alter table if exists b add constraint uq_b unique (y)",
            "create table c (id int)",
        )
        assertEquals(listOf("alter table if exists b add constraint uq_b unique (y)", "create table c (id int)"), UniqueConstraintChurn.strip(plan, setOf("ukabc")))
    }
}
