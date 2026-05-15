package app.epistola.suite.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Proves that the per-module, timestamp-versioned baseline migrations
 * (`classpath:db/migration`) reproduce a schema byte-identical to the historical
 * V1..V30 incrementing migrations.
 *
 * The historical set is snapshotted verbatim under
 * `src/test/resources/db/migration-legacy-v413/` (a path segment that is *not*
 * `db/migration`, so Flyway never auto-scans it in production).
 *
 * Mechanism: one Postgres container, two databases built by Flyway from the two
 * migration sets, then `pg_dump --schema-only` of each, normalised and compared.
 * `apps/epistola` is the only module whose (test) classpath aggregates the
 * core + feedback + loadtest migrations the way production assembles them, so
 * this gate also proves cross-module version ordering.
 *
 * Acceptance for issue #413: the normalised dumps must be equal.
 *
 * TODO(#413): remove this test and the `migration-legacy-v413/` snapshot after
 * 1.0.0 ships plus one release (the consolidation will then be long settled).
 */
@Tag("integration")
class MigrationEquivalenceIT {
    @Test
    fun `baseline migrations reproduce the historical schema`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:17")).use { pg ->
            pg.start()

            createDatabase(pg, "legacy_db")
            createDatabase(pg, "baseline_db")

            migrate(pg, "legacy_db", "classpath:db/migration-legacy-v413")
            migrate(pg, "baseline_db", "classpath:db/migration")

            val legacy = normalize(schemaDump(pg, "legacy_db"))
            val baseline = normalize(schemaDump(pg, "baseline_db"))

            if (legacy != baseline) {
                val onlyLegacy = legacy.filterNot { it in baseline.toSet() }
                val onlyBaseline = baseline.filterNot { it in legacy.toSet() }
                fail(
                    buildString {
                        appendLine("Schema drift between historical migrations and the new baseline.")
                        appendLine()
                        appendLine("--- statements only in HISTORICAL (legacy) [${onlyLegacy.size}] ---")
                        onlyLegacy.take(40).forEach { appendLine(it) }
                        appendLine()
                        appendLine("--- statements only in NEW BASELINE [${onlyBaseline.size}] ---")
                        onlyBaseline.take(40).forEach { appendLine(it) }
                    },
                )
            }
            assertEquals(legacy, baseline)
        }
    }

    private fun createDatabase(pg: PostgreSQLContainer, name: String) {
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
    }

    private fun jdbcUrlFor(pg: PostgreSQLContainer, db: String): String = "jdbc:postgresql://${pg.host}:${pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$db"

    private fun migrate(pg: PostgreSQLContainer, db: String, location: String) {
        Flyway.configure()
            .dataSource(jdbcUrlFor(pg, db), pg.username, pg.password)
            .locations(location)
            .schemas("public")
            .load()
            .migrate()
    }

    private fun schemaDump(pg: PostgreSQLContainer, db: String): String {
        val result =
            pg.execInContainer(
                "sh",
                "-c",
                "PGPASSWORD=${pg.password} pg_dump -h 127.0.0.1 -U ${pg.username} " +
                    "--schema-only --no-owner --no-privileges --schema=public $db",
            )
        check(result.exitCode == 0) {
            "pg_dump of $db failed (exit ${result.exitCode}): ${result.stderr}"
        }
        return result.stdout
    }

    /**
     * Strips dump noise (psql/SET headers, comments, blank lines), drops the
     * `flyway_schema_history` bookkeeping table (legitimately differs between
     * the two sets), normalises the wall-clock-named `generation_results`
     * monthly partitions, then splits into individual statements and sorts
     * them — pg_dump emits objects in creation (OID) order, which differs
     * because the baseline deliberately reorders migrations, so we compare
     * the canonical *set* of statements rather than their order.
     */
    private fun normalize(dump: String): List<String> {
        val cleaned =
            dump.lineSequence()
                .map { it.trimEnd() }
                .filterNot { line ->
                    line.isBlank() ||
                        line.startsWith("--") ||
                        line.startsWith("SET ") ||
                        line.startsWith("SELECT pg_catalog.set_config") ||
                        line.startsWith("\\") // psql meta: \connect, \restrict, \unrestrict
                }
                .joinToString("\n")

        return cleaned.split(Regex(";\\n"))
            .map { stmt -> stmt.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .filterNot { it.contains("flyway_schema_history") }
            .map { stmt ->
                stmt
                    .replace(Regex("generation_results_\\d{4}_\\d{2}"), "generation_results_PART")
                    .replace(
                        Regex("FOR VALUES FROM \\('[^']*'\\) TO \\('[^']*'\\)"),
                        "FOR VALUES FROM (PART) TO (PART)",
                    )
            }
            .sorted()
    }
}
