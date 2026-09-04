// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.atomic.AtomicInteger

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    /**
     * Each Spring test context gets its **own logical database** inside the one shared
     * shared Postgres container. This keeps the single-container memory/startup win
     * while restoring the per-context database isolation that integration tests rely on:
     * always-on background schedulers (`JobPoller`, `StaleJobRecovery`, …) and the global
     * `generation_results.sequence` only ever see their own context's database, so a
     * poller in one context can't drain manually-driven jobs created by another (the bug
     * that a single shared database introduced).
     *
     * This `@TestConfiguration` is instantiated once per context, so each context creates
     * exactly one database; the name is stable for that context and does not perturb the
     * Spring context cache key. We expose a [JdbcConnectionDetails] bean (not a
     * `@ServiceConnection` container) so Spring Boot wires the datasource — and therefore
     * Flyway and JDBI — to the per-context database. The database is a clone of the JVM's
     * migrated [TemplateDatabase], so Flyway on startup only validates instead of replaying
     * every migration per context.
     */
    @Bean
    fun postgresConnectionDetails(): JdbcConnectionDetails {
        val postgres = TestRuntimeLifecycle.postgres()
        val databaseName = "ctx_${DATABASE_SEQUENCE.incrementAndGet()}"
        TemplateDatabase.createClone(postgres, databaseName)
        val jdbcUrl = TemplateDatabase.databaseUrl(postgres, databaseName)
        return object : JdbcConnectionDetails {
            override fun getUsername(): String = postgres.username
            override fun getPassword(): String = postgres.password
            override fun getJdbcUrl(): String = jdbcUrl
        }
    }

    companion object {
        private val DATABASE_SEQUENCE = AtomicInteger(0)
    }
}
