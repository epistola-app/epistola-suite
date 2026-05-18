package app.epistola.suite.migration

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.Configuration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Guardrail for the design's core invariant.
 *
 * The single [app.epistola.suite.config.FlywayConfig] seam is shared, but the
 * app and the isolated migration context compose Flyway via *different*
 * auto-config sets (the app: full Spring Boot auto-configuration; the launcher:
 * a hand-curated `@ImportAutoConfiguration`). If the launcher's resolved Flyway
 * configuration ever drifts from the app's — e.g. a Spring Boot upgrade
 * reshuffles auto-config, or Flyway gains a collaborator the launcher doesn't
 * import — migrations would run with subtly different settings than the app
 * validates against. That is exactly the migrate/runtime drift this design
 * exists to prevent, so this test fails loudly the moment it happens.
 *
 * Intentionally-different settings are excluded: `clean-disabled` (app default
 * false vs. the migration step forcing true) and the datasource.
 */
class MigrationFlywayConfigEquivalenceTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var appFlyway: Flyway

    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    @Test
    fun `migration context resolves the same Flyway configuration as the app`() {
        SpringApplicationBuilder(MigrationLauncher.MigrationConfig::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--epistola.migration.mode=migrate",
                "--spring.flyway.clean-disabled=true",
            ).use { ctx ->
                val migrationFlyway = ctx.getBean(Flyway::class.java)
                assertThat(snapshot(migrationFlyway.configuration))
                    .describedAs(
                        "Isolated migration Flyway config drifted from the app's. " +
                            "Re-align MigrationLauncher.MigrationConfig's @ImportAutoConfiguration.",
                    )
                    .isEqualTo(snapshot(appFlyway.configuration))
            }
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
