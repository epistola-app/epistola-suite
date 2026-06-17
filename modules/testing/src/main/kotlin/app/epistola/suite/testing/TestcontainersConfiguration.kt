package app.epistola.suite.testing

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
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
     * Flyway and JDBI — to the per-context database; Flyway then migrates it on startup,
     * exactly as it did when each context had its own container.
     */
    @Bean
    fun postgresConnectionDetails(): JdbcConnectionDetails {
        val postgres = TestRuntimeLifecycle.postgres()
        val databaseName = "ctx_${DATABASE_SEQUENCE.incrementAndGet()}"
        createDatabase(postgres, databaseName)
        val jdbcUrl = perContextJdbcUrl(postgres, databaseName)
        return object : JdbcConnectionDetails {
            override fun getUsername(): String = postgres.username
            override fun getPassword(): String = postgres.password
            override fun getJdbcUrl(): String = jdbcUrl
        }
    }

    private fun createDatabase(
        postgres: PostgreSQLContainer,
        databaseName: String,
    ) {
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        ).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE \"$databaseName\"") }
        }
    }

    private fun perContextJdbcUrl(
        postgres: PostgreSQLContainer,
        databaseName: String,
    ): String {
        val queryParams = postgres.jdbcUrl.substringAfter('?', "")
        val base = "jdbc:postgresql://${postgres.host}:" +
            "${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$databaseName"
        return if (queryParams.isEmpty()) base else "$base?$queryParams"
    }

    companion object {
        private val DATABASE_SEQUENCE = AtomicInteger(0)
    }
}
