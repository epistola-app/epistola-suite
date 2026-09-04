// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * One migrated template database per test JVM, cloned for every Spring test context.
 *
 * Each context still gets its own logical database (see [TestcontainersConfiguration] for
 * why), but instead of replaying every Flyway migration into an empty database — the dominant
 * cost of a context boot, paid again for each of the dozen-plus contexts a module boots — the
 * database is created with `CREATE DATABASE … TEMPLATE`, a file copy that takes a fraction of a
 * second. Flyway in the new context then finds a complete `flyway_schema_history` and only
 * validates.
 *
 * The template is migrated with the same inputs Spring Boot's auto-configuration uses: the
 * JVM's classpath under `db/migration` (so a module's template holds exactly the migrations
 * that module's tests would have applied) and Flyway's defaults. Nothing in the application
 * customizes locations, placeholders or callbacks (`FlywayConfig` only chooses between migrate
 * and validate), and `MigrationLauncherIntegrationTest` guards that equivalence. The UNLOGGED
 * conversion that [UnloggedTablesTestConfiguration] performs per context is done here once;
 * table persistence is copied along with the tables, so the per-context runner has nothing
 * left to do.
 *
 * Postgres refuses to clone a database that has sessions, so the migration connections are
 * closed before the first clone, and clones are serialized on the same lock.
 */
object TemplateDatabase {
    const val NAME = "ctx_template"

    private val logger = LoggerFactory.getLogger(TemplateDatabase::class.java)
    private val lock = Any()

    @Volatile
    private var built = false

    /** Creates [databaseName] as a copy of the migrated template, building the template first if needed. */
    fun createClone(
        postgres: PostgreSQLContainer,
        databaseName: String,
    ) {
        synchronized(lock) {
            if (!built) {
                build(postgres)
                built = true
            }
            adminConnection(postgres).use { connection ->
                connection.createStatement().use {
                    it.execute("CREATE DATABASE \"$databaseName\" TEMPLATE \"$NAME\"")
                }
            }
        }
    }

    /** JDBC URL for [databaseName] inside [postgres], keeping the container's query parameters. */
    fun databaseUrl(
        postgres: PostgreSQLContainer,
        databaseName: String,
    ): String {
        val queryParams = postgres.jdbcUrl.substringAfter('?', "")
        val base = "jdbc:postgresql://${postgres.host}:" +
            "${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$databaseName"
        return if (queryParams.isEmpty()) base else "$base?$queryParams"
    }

    private fun build(postgres: PostgreSQLContainer) {
        val startNanos = System.nanoTime()
        adminConnection(postgres).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE \"$NAME\"") }
        }
        val url = databaseUrl(postgres, NAME)
        // A plain driver data source: Flyway opens what it needs and closes it when
        // migrate() returns, which is what leaves the template free of sessions.
        Flyway.configure()
            .dataSource(url, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .load()
            .migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use(::convertTablesToUnlogged)
        logger.info("Built template database {} in {} ms", NAME, (System.nanoTime() - startNanos) / 1_000_000)
    }

    private fun adminConnection(postgres: PostgreSQLContainer): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    /**
     * Same conversion as [UnloggedTablesTestConfiguration], on a raw connection. Foreign keys
     * require both sides to share persistence, so the loop retries until nothing is left.
     */
    private fun convertTablesToUnlogged(connection: Connection) {
        repeat(10) {
            val remaining = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT tablename FROM pg_tables
                    WHERE schemaname = 'public'
                    AND tablename NOT IN ('flyway_schema_history')
                    AND tablename NOT IN (
                        SELECT c.relname FROM pg_class c WHERE c.relpersistence = 'u'
                    )
                    """.trimIndent(),
                ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
            if (remaining.isEmpty()) return
            for (table in remaining) {
                runCatching {
                    connection.createStatement().use { it.execute("ALTER TABLE \"$table\" SET UNLOGGED") }
                }
            }
        }
    }
}
