package app.epistola.suite

import app.epistola.suite.migration.MigrationLauncher
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import kotlin.system.exitProcess

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class EpistolaSuiteApplication

fun main(args: Array<String>) {
    // EPISTOLA_MIGRATION_MODE=migrate (or --migrate): run migrations in an
    // isolated context and exit, without ever creating the full application
    // context. run() never returns (it calls exitProcess).
    if (MigrationLauncher.requested(args)) {
        MigrationLauncher.run(args)
    }
    try {
        runApplication<EpistolaSuiteApplication>(*args)
    } catch (e: Throwable) {
        // Spring Boot has already logged the cause. Force a deterministic
        // non-zero exit so a validate-mode fail-fast (separated deployments
        // whose DB is behind) reliably gates the pod / deploy.
        exitProcess(1)
    }
}
