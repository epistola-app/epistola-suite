package app.epistola.suite.migration

import app.epistola.suite.config.FlywayConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import kotlin.system.exitProcess

/**
 * Runs Flyway migrations in an isolated Spring context, then exits the JVM.
 *
 * Selected in `main()` — before the full application boots — by the
 * `EPISTOLA_RUN_MODE=migrate` environment variable (the Helm chart wires this in
 * `job` / `initContainer` modes) or a `--migrate` program argument.
 *
 * The context imports only the datasource and Flyway auto-configuration plus
 * [FlywayConfig], so none of the application's components (web server, schedulers,
 * demo loader, catalog bootstrap) are ever loaded — there is nothing to gate. The
 * same `application.yaml` loads, so `spring.flyway.locations` and the
 * `SPRING_DATASOURCE_*` datasource config apply identically to the embedded path
 * (no Flyway-config duplication, no migrate/runtime drift).
 *
 * Always migrates and never auto-cleans (`spring.flyway.clean-disabled=true`): a
 * failed migration must surface as a non-zero exit code so it gates the deploy,
 * never as a destructive reset.
 */
object MigrationLauncher {

    private val logger = LoggerFactory.getLogger(javaClass)

    private const val RUN_MODE_ENV = "EPISTOLA_RUN_MODE"
    private const val MIGRATE = "migrate"

    /**
     * True when this process was launched to run migrations only. Checked before
     * Spring starts so the full application context is never created.
     */
    fun requested(args: Array<String>): Boolean = System.getenv(RUN_MODE_ENV)?.equals(MIGRATE, ignoreCase = true) == true ||
        args.any { it == "--$MIGRATE" }

    /**
     * Boot the isolated migration context, run Flyway, and terminate the JVM with
     * exit code 0 on success or 1 on failure. Never returns.
     */
    fun run(args: Array<String>) {
        logger.info("Starting in migration mode — isolated context, no web server")
        val exitCode =
            try {
                SpringApplicationBuilder(MigrationConfig::class.java)
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
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
        // exitProcess (not just context close) guarantees JVM termination with the
        // intended code even though virtual threads are enabled globally.
        exitProcess(exitCode)
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(DataSourceAutoConfiguration::class, FlywayAutoConfiguration::class)
    @Import(FlywayConfig::class)
    class MigrationConfig
}
