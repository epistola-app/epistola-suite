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

/**
 * Spring Boot DevTools restarts the app in a dedicated classloader: on the
 * `ApplicationStartingEvent` (early inside `runApplication`) it launches the
 * real application on the `restartedMain` thread, then unwinds the *original*
 * main thread by throwing this internal exception
 * (`SilentExitExceptionHandler.exitCurrentThread()`). DevTools also installs
 * that thread's `UncaughtExceptionHandler` so the exception is swallowed when
 * it propagates out of `main` — the original thread dies cleanly while the app
 * keeps running on `restartedMain`. So the correct handling is to **let it
 * propagate** (re-throw), exactly as the pre-#431 bare `runApplication(...)`
 * did. It is thrown bare (never wrapped) and is package-private, and DevTools
 * is `developmentOnly` (never on the prod/CI classpath), so an exact
 * fully-qualified-name match is the right — and only — way to recognise it.
 */
private fun isDevToolsSilentExit(e: Throwable): Boolean = e.javaClass.name == "org.springframework.boot.devtools.restart.SilentExitExceptionHandler\$SilentExitException"

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
        // A DevTools restart is not a failure — let it unwind normally so the
        // app keeps running, instead of force-exiting the JVM (which would kill
        // local `bootRun` right after a successful startup).
        if (isDevToolsSilentExit(e)) {
            throw e
        }
        // A genuine startup failure: Spring Boot has already logged the cause.
        // Force a deterministic non-zero exit so a validate-mode fail-fast
        // (separated deployments whose DB is behind) reliably gates the
        // pod / deploy. (DevTools absent here ⇒ no SilentExitException path.)
        exitProcess(1)
    }
}
