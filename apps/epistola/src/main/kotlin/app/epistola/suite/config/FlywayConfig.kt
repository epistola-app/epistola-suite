package app.epistola.suite.config

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.exception.FlywayValidateException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Controls how Flyway behaves at context startup via `epistola.migration.mode`.
 *
 * Spring Boot's Flyway auto-configuration invokes this [FlywayMigrationStrategy]
 * bean instead of calling `flyway.migrate()` itself, so this single seam governs
 * both the embedded app path and the isolated migration context (which imports
 * this same bean — no duplicated Flyway config, no migrate/runtime drift).
 *
 * - `migrate` (default — local/dev embedded, and the isolated migration step):
 *   run `flyway.migrate()`. Replaces Flyway's removed `cleanOnValidationError`
 *   property: on a validation failure, if cleaning is allowed
 *   (`spring.flyway.clean-disabled=false`) the database is cleaned and migrations
 *   re-run; otherwise the failure propagates. `migrate()` is idempotent, so this
 *   is a no-op when the schema is already at head.
 * - `validate` (separated deployments — set by `application-prod.yaml` and by the
 *   Helm chart in `job`/`initContainer` modes): never migrate or clean. Validate
 *   the schema and fail fast if the database is behind, so app pods refuse to
 *   start until the separate migration step has run.
 */
@Configuration
class FlywayConfig {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun flywayMigrationStrategy(
        @Value("\${spring.flyway.clean-disabled:true}") cleanDisabled: Boolean,
        @Value("\${epistola.migration.mode:migrate}") mode: String,
    ): FlywayMigrationStrategy {
        val migrationMode = MigrationMode.from(mode)
        return FlywayMigrationStrategy { flyway ->
            when (migrationMode) {
                MigrationMode.MIGRATE -> migrate(flyway, cleanDisabled)
                MigrationMode.VALIDATE -> validate(flyway)
            }
        }
    }

    private fun migrate(
        flyway: Flyway,
        cleanDisabled: Boolean,
    ) {
        try {
            flyway.migrate()
        } catch (e: FlywayValidateException) {
            if (cleanDisabled) throw e
            logger.warn("Migration validation failed — cleaning database and re-migrating: {}", e.message)
            flyway.clean()
            flyway.migrate()
        }
    }

    private fun validate(flyway: Flyway) {
        logger.info(
            "Flyway validate-only mode: the schema must be migrated by a separate step; " +
                "this process will not migrate or clean the database",
        )
        // Flyway's own validate() fails fast when the DB is behind (pending
        // migrations). The explicit pending() check below is a belt-and-
        // suspenders net for any Flyway config that tolerates pending. Both
        // paths surface as one clear, actionable IllegalStateException.
        try {
            flyway.validate()
        } catch (e: FlywayValidateException) {
            logger.error("Flyway validation failed: {}", e.message)
            throw IllegalStateException(schemaBehindMessage(flyway), e)
        }
        if (flyway.info().pending().isNotEmpty()) {
            throw IllegalStateException(schemaBehindMessage(flyway))
        }
    }

    private fun schemaBehindMessage(flyway: Flyway): String {
        val pending = runCatching {
            flyway.info().pending().map { it.version?.toString() ?: it.description }
        }.getOrDefault(emptyList())
        val detail = if (pending.isEmpty()) "" else " (${pending.size} pending: ${pending.joinToString(", ")})"
        return "Database schema is behind$detail. Run the migration step " +
            "(EPISTOLA_RUN_MODE=migrate / Helm migration Job) before starting the application."
    }

    enum class MigrationMode {
        MIGRATE,
        VALIDATE,
        ;

        companion object {
            fun from(value: String): MigrationMode = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalStateException(
                    "Invalid epistola.migration.mode='$value' (expected 'migrate' or 'validate')",
                )
        }
    }
}
