package app.epistola.suite

import app.epistola.suite.migration.MigrationLauncher
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

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
    // Bare runApplication: validate-mode fail-fast throws SchemaBehindException
    // (an ExitCodeGenerator) so Spring Boot's SpringBootExceptionHandler
    // System.exit()s non-zero deterministically — the deploy gate — with no
    // try/catch here. Any other startup failure exits the same way. DevTools'
    // SilentExitException propagates untouched to DevTools' own handler, so
    // local `bootRun` keeps running across restarts.
    runApplication<EpistolaSuiteApplication>(*args)
}
