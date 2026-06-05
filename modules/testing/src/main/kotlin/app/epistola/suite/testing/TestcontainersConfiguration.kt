package app.epistola.suite.testing

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    /**
     * Each Spring test context gets its **own logical database** inside the one shared
     * [SHARED_POSTGRES] container. This keeps the single-container memory/startup win
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
        val databaseName = "ctx_${DATABASE_SEQUENCE.incrementAndGet()}"
        createDatabase(databaseName)
        val jdbcUrl = perContextJdbcUrl(databaseName)
        return object : JdbcConnectionDetails {
            override fun getUsername(): String = SHARED_POSTGRES.username
            override fun getPassword(): String = SHARED_POSTGRES.password
            override fun getJdbcUrl(): String = jdbcUrl
        }
    }

    private fun createDatabase(databaseName: String) {
        DriverManager.getConnection(
            SHARED_POSTGRES.jdbcUrl,
            SHARED_POSTGRES.username,
            SHARED_POSTGRES.password,
        ).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE \"$databaseName\"") }
        }
    }

    private fun perContextJdbcUrl(databaseName: String): String {
        val queryParams = SHARED_POSTGRES.jdbcUrl.substringAfter('?', "")
        val base = "jdbc:postgresql://${SHARED_POSTGRES.host}:" +
            "${SHARED_POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$databaseName"
        return if (queryParams.isEmpty()) base else "$base?$queryParams"
    }

    companion object {
        private val DATABASE_SEQUENCE = AtomicInteger(0)

        /**
         * One Postgres container shared by every test context in the JVM. It is
         * intentionally a plain static (not a Spring bean), so Spring never starts/stops it
         * per context; per-context databases (above) provide isolation. The data directory
         * is RAM-backed (tmpfs) for speed.
         *
         * Cleanup is via an explicit JVM shutdown hook rather than relying on Testcontainers'
         * Ryuk reaper: Ryuk is unavailable or disabled in several environments (rootless
         * Docker, some Docker-in-Docker / Podman setups, `TESTCONTAINERS_RYUK_DISABLED=true`,
         * security-restricted CI). The hook stops the container on graceful test-JVM exit;
         * Ryuk, when present, remains a backstop for non-graceful termination.
         */
        @JvmStatic
        private val SHARED_POSTGRES: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
                .apply {
                    start()
                    Runtime.getRuntime().addShutdownHook(Thread { stop() })
                }
    }
}
