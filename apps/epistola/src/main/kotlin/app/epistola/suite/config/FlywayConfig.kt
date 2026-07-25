// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.exception.FlywayValidateException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Validate-mode fail-fast: the database schema is behind and this process must
 * not start. Implements [ExitCodeGenerator] so Spring Boot deterministically
 * `System.exit`s with a non-zero code when this propagates out of a failed
 * `SpringApplication.run()` (the K8s deploy gate) — no `try/catch` or manual
 * `exitProcess` in `main()`. Spring Boot's exit-code resolver walks the
 * exception cause chain, so the surrounding `BeanCreationException` wrapper
 * does not hide it. Still an [IllegalStateException] for source compatibility.
 */
class SchemaBehindException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause),
    ExitCodeGenerator {
    override fun getExitCode(): Int = 1
}

/**
 * Controls how Flyway behaves at context startup via the single
 * `epistola.migration.mode` knob (env `EPISTOLA_MIGRATION_MODE`).
 *
 * Spring Boot's Flyway auto-configuration invokes this [FlywayMigrationStrategy]
 * bean instead of calling `flyway.migrate()` itself, so this single seam governs
 * both the embedded app path and the isolated migration context (which imports
 * this same bean — no duplicated Flyway config, no migrate/runtime drift).
 *
 * The mode is one knob with three unambiguous values (see [MigrationMode]):
 *
 * - `embedded` (default — local/dev): the app runs `flyway.migrate()` at boot.
 * - `migrate`: the dedicated migration step (set via the env var by the Helm
 *   Job / init container; `main()` detects it pre-Spring and runs the isolated
 *   `MigrationLauncher`, whose context forces this value). Also `flyway.migrate()`.
 * - `validate`: separated app pods (set by `application-prod.yaml` and by the
 *   Helm chart in `job`/`initContainer` modes). Never migrate or clean —
 *   validate and fail fast if the database is behind, so app pods refuse to
 *   start until the separate migration step has run.
 *
 * `embedded`/`migrate` run `flyway.migrate()` (idempotent — a no-op when already
 * at head). **The application never resets a database:** there is no
 * `flyway.clean()` call anywhere, in any profile or build. A validation failure
 * (a previously applied migration was modified, or local data diverged) fails
 * fast — it is never "recovered" by wiping data. This is the hard guarantee
 * behind the RC1 promise that data persists across versions: a reset simply is
 * not a capability the running app has.
 *
 * Resetting a local dev database is an explicit, out-of-band developer action —
 * recreate the ephemeral (tmpfs) Postgres container (`./gradlew resetLocalDb`,
 * see [docs/migrations.md]) — not something the app does on boot.
 */
@Configuration
class FlywayConfig {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun flywayMigrationStrategy(
        @Value("\${epistola.migration.mode:embedded}") mode: String,
    ): FlywayMigrationStrategy {
        val migrationMode = MigrationMode.from(mode)
        return FlywayMigrationStrategy { flyway ->
            when (migrationMode) {
                MigrationMode.EMBEDDED, MigrationMode.MIGRATE -> migrate(flyway)
                MigrationMode.VALIDATE -> validate(flyway)
            }
        }
    }

    private fun migrate(flyway: Flyway) {
        try {
            flyway.migrate()
        } catch (e: FlywayValidateException) {
            // The app never cleans/recovers by wiping data. Fail fast with a
            // deterministic exit code (SchemaBehindException) and audience-neutral
            // guidance: local dev recreates its throwaway DB container; a deployed
            // environment must investigate a modified released migration.
            logger.error("Flyway migration validation failed: {}", e.message)
            throw SchemaBehindException(
                "Flyway validation failed during migrate — the schema does not match the migrations " +
                    "(a previously applied migration was modified, or the database diverged). This process " +
                    "never resets a database. Local dev: recreate the DB container with `./gradlew resetLocalDb` " +
                    "and retry. Deployed: a released migration was altered — investigate; do NOT reset.",
                e,
            )
        }
    }

    private fun validate(flyway: Flyway) {
        logger.info(
            "Flyway validate-only mode: the schema must be migrated by a separate step; " +
                "this process will not migrate or clean the database",
        )
        // With default Flyway config, validate() itself fails fast on a behind
        // DB ("Detected resolved migration not applied"). The explicit pending()
        // check below is NOT dead code: it is the failure path when Flyway is
        // configured to tolerate pending migrations (e.g.
        // `spring.flyway.ignore-migration-patterns=*:pending`). Both paths
        // surface as one clear, actionable IllegalStateException.
        try {
            flyway.validate()
        } catch (e: FlywayValidateException) {
            logger.error("Flyway validation failed: {}", e.message)
            throw SchemaBehindException(schemaBehindMessage(flyway), e)
        }
        if (flyway.info().pending().isNotEmpty()) {
            throw SchemaBehindException(schemaBehindMessage(flyway))
        }
    }

    private fun schemaBehindMessage(flyway: Flyway): String {
        val pending = runCatching {
            flyway.info().pending().map { it.version?.toString() ?: it.description }
        }.getOrDefault(emptyList())
        val detail = if (pending.isEmpty()) "" else " (${pending.size} pending: ${pending.joinToString(", ")})"
        return "Database schema is behind$detail. Run the migration step " +
            "(EPISTOLA_MIGRATION_MODE=migrate / Helm migration Job) before starting the application."
    }

    /**
     * The single migration knob. One name, three meanings — no overloaded
     * tokens, so `migrate` always means "dedicated migration step" and never
     * leaks in as a property default.
     */
    enum class MigrationMode {
        /** App migrates at boot (default; local/dev, single-process). */
        EMBEDDED,

        /** Dedicated migration step: isolated context migrates then exits. */
        MIGRATE,

        /** App only validates the schema and fails fast if it is behind. */
        VALIDATE,
        ;

        companion object {
            fun from(value: String): MigrationMode = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalStateException(
                    "Invalid epistola.migration.mode='$value' (expected 'embedded', 'migrate' or 'validate')",
                )
        }
    }
}
