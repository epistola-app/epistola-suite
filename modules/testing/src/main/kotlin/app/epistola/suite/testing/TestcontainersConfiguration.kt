package app.epistola.suite.testing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    /**
     * Every Spring test context that imports this config wires its datasource to the
     * SAME [SHARED_POSTGRES] instance. Returning the shared static (rather than a
     * per-`@Bean` container) means the whole JVM runs a single Postgres regardless of
     * how many distinct contexts the suite caches.
     */
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = SHARED_POSTGRES

    companion object {
        /**
         * One Postgres container shared by every test context in the JVM.
         *
         * The suite isolates tests **logically** (namespaced-tenant cleanup, see
         * `BaseIntegrationTest.resetDatabaseState`), not via a database-per-context, so
         * a single container is sufficient. Previously this was a plain per-context
         * `@Bean`, so each distinct cached context started its own container — a ~6×
         * container-RAM cost (each with a tmpfs, i.e. RAM-backed, data dir) and the
         * source of container-startup contention that flaked context loads.
         *
         * Started once here and reaped by Ryuk at JVM exit; it is intentionally never
         * stopped between contexts. (`withReuse` is deliberately absent — it only helps
         * across separate JVM runs and silently no-ops unless each developer/CI enables
         * `testcontainers.reuse.enable`, so it never solved the in-JVM duplication.)
         */
        @JvmStatic
        private val SHARED_POSTGRES: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
                .apply { start() }
    }
}
