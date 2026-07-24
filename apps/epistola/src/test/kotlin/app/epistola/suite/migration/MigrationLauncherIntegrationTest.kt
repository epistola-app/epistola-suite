// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.bootstrap.SystemCatalogBootstrap
import app.epistola.suite.demo.DemoLoader
import app.epistola.suite.documents.cleanup.PartitionMaintenanceScheduler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.Configuration
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.web.context.WebApplicationContext

/**
 * Exercises the isolated migration context via the exact production
 * construction [MigrationLauncher.migrationApplication] against the shared
 * Testcontainer (already migrated by the test context, so `migrate` is
 * idempotent and `validate` sees no pending migrations).
 *
 * We run the builder directly rather than [MigrationLauncher.run], which
 * calls `exitProcess` and would kill the test JVM.
 */
class MigrationLauncherIntegrationTest : BaseIntegrationTest() {

    // The database this test context already migrated (its own per-context DB inside the
    // shared container). Running the migration launcher against it keeps `migrate`
    // idempotent and `validate` clean — see class KDoc.
    @Autowired
    private lateinit var connectionDetails: JdbcConnectionDetails

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var appFlyway: Flyway

    // Built via the exact production factory (web=NONE, banner off,
    // LoggingApplicationListener stripped). Properties go through command-line
    // args (high precedence) so they override application.yaml — mirrors
    // MigrationLauncher.runMigration.
    private fun runMigrationContext(vararg props: String) = MigrationLauncher.migrationApplication()
        .run(
            "--spring.datasource.url=${connectionDetails.jdbcUrl}",
            "--spring.datasource.username=${connectionDetails.username}",
            "--spring.datasource.password=${connectionDetails.password}",
            *props.map { "--$it" }.toTypedArray(),
        )

    @Test
    fun `migrate mode boots an isolated context, runs Flyway, loads no app beans`() {
        runMigrationContext("epistola.migration.mode=migrate", "spring.flyway.clean-disabled=true")
            .use { ctx ->
                assertThat(ctx).isNotInstanceOf(WebApplicationContext::class.java)
                assertThat(ctx.getBeanNamesForType(Flyway::class.java)).isNotEmpty()
                assertThat(snapshot(ctx.getBean(Flyway::class.java).configuration))
                    .describedAs(
                        "Isolated migration Flyway config drifted from the app's. " +
                            "Re-align MigrationLauncher.MigrationConfig's @ImportAutoConfiguration.",
                    )
                    .isEqualTo(snapshot(appFlyway.configuration))
                // The migration context never component-scans the app.
                assertThat(ctx.getBeanNamesForType(PartitionMaintenanceScheduler::class.java)).isEmpty()
                assertThat(ctx.getBeanNamesForType(DemoLoader::class.java)).isEmpty()
                assertThat(ctx.getBeanNamesForType(SystemCatalogBootstrap::class.java)).isEmpty()
            }
        assertThat(appliedCount()).isGreaterThan(0)
    }

    @Test
    fun `validate mode starts when the schema is at head and migrates nothing`() {
        val before = appliedCount()
        runMigrationContext("epistola.migration.mode=validate")
            .use { ctx ->
                assertThat(ctx.getBeanNamesForType(Flyway::class.java)).isNotEmpty()
            }
        assertThat(appliedCount()).isEqualTo(before)
    }

    @Test
    fun `validate mode fails fast when the database is behind`() {
        assertThatThrownBy {
            runMigrationContext(
                "epistola.migration.mode=validate",
                // Adds an unapplied probe migration; validate never migrates, so
                // it stays pending and the shared database is left untouched.
                "spring.flyway.locations=classpath:db/migration,classpath:probe-migration",
            )
        }.hasStackTraceContaining("Database schema is behind")
            // Locks in the ExitCodeGenerator path: validate fail-fast must be
            // a SchemaBehindException so Spring Boot sets the non-zero exit.
            .hasStackTraceContaining("SchemaBehindException")
    }

    @Test
    fun `runMigration returns 0 on success`() {
        val code = MigrationLauncher.runMigration(
            arrayOf(
                "--spring.datasource.url=${connectionDetails.jdbcUrl}",
                "--spring.datasource.username=${connectionDetails.username}",
                "--spring.datasource.password=${connectionDetails.password}",
            ),
        )
        assertThat(code).isEqualTo(0)
    }

    @Test
    fun `runMigration returns 1 when the database is unreachable`() {
        val code = MigrationLauncher.runMigration(
            arrayOf(
                "--spring.datasource.url=jdbc:postgresql://localhost:1/nope",
                "--spring.datasource.username=x",
                "--spring.datasource.password=x",
                "--spring.flyway.connect-retries=0",
            ),
        )
        assertThat(code).isEqualTo(1)
    }

    private fun appliedCount(): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM flyway_schema_history WHERE success")
            .mapTo(Int::class.java)
            .one()
    }

    /** The Flyway settings that actually affect what/how migrations apply. */
    private fun snapshot(cfg: Configuration): Map<String, Any?> = mapOf(
        "locations" to cfg.locations.map { it.toString() }.sorted(),
        "defaultSchema" to cfg.defaultSchema,
        "schemas" to cfg.schemas.sorted(),
        "table" to cfg.table,
        "placeholders" to cfg.placeholders,
        "placeholderPrefix" to cfg.placeholderPrefix,
        "placeholderSuffix" to cfg.placeholderSuffix,
        "sqlMigrationPrefix" to cfg.sqlMigrationPrefix,
        "sqlMigrationSuffixes" to cfg.sqlMigrationSuffixes.toList(),
        "repeatableSqlMigrationPrefix" to cfg.repeatableSqlMigrationPrefix,
        "baselineOnMigrate" to cfg.isBaselineOnMigrate,
        "baselineVersion" to cfg.baselineVersion?.toString(),
        "createSchemas" to cfg.isCreateSchemas,
        "callbacks" to cfg.callbacks.map { it.javaClass.name }.sorted(),
    )
}
