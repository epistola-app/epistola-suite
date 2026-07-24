// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.config.FlywayConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.logging.LoggingApplicationListener
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import kotlin.system.exitProcess

/**
 * Runs Flyway migrations in an isolated Spring context, then exits the JVM.
 *
 * Selected in `main()` — before the full application boots — by the single
 * `EPISTOLA_MIGRATION_MODE=migrate` environment variable (the Helm chart wires
 * this in `job` / `initContainer` modes) or a `--migrate` program argument. This
 * is the same knob [FlywayConfig] reads; `migrate` is reserved for "the
 * dedicated migration step" and is never an `application.yaml` default, so the
 * embedded default (`embedded`) can never accidentally trigger the launcher.
 *
 * The context imports only the datasource and Flyway auto-configuration plus
 * [FlywayConfig], so none of the application's components (web server, schedulers,
 * demo loader, catalog bootstrap) are ever loaded — there is nothing to gate. The
 * same `application.yaml` loads, so `spring.flyway.locations` and the
 * `SPRING_DATASOURCE_*` datasource config apply identically to the embedded path.
 *
 * NOTE: the *seam* ([FlywayConfig]) and the Flyway config inputs (locations,
 * placeholders) are identical to the app, but the two contexts compose Flyway
 * via different auto-config sets. `MigrationFlywayConfigEquivalenceTest` is the
 * guardrail that fails loudly if those ever drift.
 *
 * Always migrates and never auto-cleans (`spring.flyway.clean-disabled=true`): a
 * failed migration must surface as a non-zero exit code so it gates the deploy,
 * never as a destructive reset.
 */
object MigrationLauncher {

    private val logger = LoggerFactory.getLogger(javaClass)

    const val MIGRATION_MODE_ENV = "EPISTOLA_MIGRATION_MODE"
    private const val MIGRATE = "migrate"

    /**
     * True when this process was launched as the dedicated migration step.
     * Pure: the environment value is passed in so it is unit-testable without
     * depending on the ambient JVM environment.
     */
    fun requested(
        args: Array<String>,
        migrationModeEnv: String? = System.getenv(MIGRATION_MODE_ENV),
    ): Boolean = migrationModeEnv?.equals(MIGRATE, ignoreCase = true) == true ||
        args.any { it == "--$MIGRATE" }

    /**
     * Boot the isolated migration context, run Flyway, and terminate the JVM
     * with the exit code from [runMigration]. Never returns.
     */
    fun run(args: Array<String>): Nothing {
        // exitProcess (not just context close) guarantees JVM termination with
        // the intended code even though virtual threads are enabled globally.
        exitProcess(runMigration(args))
    }

    /**
     * The isolated migration [SpringApplicationBuilder] — the single
     * construction used by both production ([runMigration]) and the migration
     * tests, so they can never drift (this is what
     * `MigrationFlywayConfigEquivalenceTest` asserts).
     *
     * `LoggingApplicationListener` is deliberately excluded: this short-lived,
     * isolated runner must NOT own the JVM-global Logback `LoggerContext`.
     * Spring Boot registers a logging `SmartLifecycle` for root contexts whose
     * `stop()` (on context close) runs `LogbackLoggingSystem.cleanUp()` on the
     * shared singleton — which, in the single Gradle test JVM, tears logging
     * down for sibling `@SpringBootTest`s. Without the listener the migration
     * context neither initialises nor cleans the shared logging system.
     *
     * Trade-off: `logback-spring.xml` is applied by that listener (not
     * auto-loaded by Logback), so the migration process logs at Logback's
     * built-in default formatting. Acceptable for a one-shot migration runner —
     * Flyway output and exit codes are unaffected.
     */
    internal fun migrationApplication(): SpringApplicationBuilder {
        val builder = SpringApplicationBuilder(MigrationConfig::class.java)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
        builder.application().setListeners(
            builder.application().listeners.filterNot { it is LoggingApplicationListener },
        )
        return builder
    }

    /**
     * Boot the isolated migration context, run Flyway, close it, and return the
     * process exit code (0 success / 1 failure). Pure of `exitProcess` so the
     * exit contract — the thing the deploy gate depends on — is testable.
     */
    fun runMigration(args: Array<String>): Int {
        logger.info("Starting in migration mode — isolated context, no web server")
        return try {
            migrationApplication()
                // Passed as command-line args (high precedence) so they
                // override application.yaml. `properties()` would register
                // them as *default* properties (lowest precedence) and
                // application.yaml's clean-disabled:false would win.
                .run(
                    *args,
                    "--epistola.migration.mode=migrate",
                    "--spring.flyway.clean-disabled=true",
                )
                .close()
            logger.info("Database migration completed successfully")
            0
        } catch (e: Throwable) {
            logger.error("Database migration failed", e)
            1
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(DataSourceAutoConfiguration::class, FlywayAutoConfiguration::class)
    @Import(FlywayConfig::class)
    class MigrationConfig
}
