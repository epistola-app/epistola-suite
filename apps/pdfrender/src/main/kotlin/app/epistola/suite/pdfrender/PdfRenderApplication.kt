package app.epistola.suite.pdfrender

import app.epistola.suite.pdfrender.PdfRenderApplication.Companion.BASE_PACKAGE
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Slim, headless render worker: it polls the shared job queue for pending document
 * generation requests and renders PDFs, and does nothing else.
 *
 * **Beta.** This app is wired and functional but not yet supported for production; its
 * deployment shape (image, Helm wiring, the limited DB role) is still settling, and the
 * `pdf-render` capability and `epistola.generation.pdf-render.enabled` property may change.
 * Per the project's maturity policy, breaking changes to it may ship in a MINOR release.
 *
 * It reuses the suite's business logic wholesale — the entire render/job pipeline lives in
 * `epistola-core`, which is the only module this app depends on — but stays slim by *not*
 * depending on the UI, REST, MCP, or commercial-support modules, so none of those beans are
 * on the classpath to be component-scanned.
 *
 * What keeps it to rendering only is cluster capability routing, not a pile of disabled
 * schedulers: this app advertises the `pdf-render` capability alone (`epistola.cluster.capabilities`
 * in its `application.yaml`), and every non-render scheduled task requires the `suite`
 * capability, so a `pdf-render`-only node can never claim partition maintenance, content reaping,
 * quality sweeps, hub sync, log retention, etc. It also never migrates — Flyway is disabled and
 * `epistola.migration.mode=validate` only reads the schema history — so it is safe behind a
 * limited, no-DDL database user. The suite (or a dedicated migration Job) remains the sole
 * owner of the schema.
 *
 * Unlike [EpistolaSuiteApplication] there is no `UserDetailsServiceAutoConfiguration` exclude
 * (no security starter is on the classpath) and no `MigrationLauncher` branch (this app never
 * runs migrations).
 */
@SpringBootApplication(scanBasePackages = [BASE_PACKAGE])
@ConfigurationPropertiesScan(BASE_PACKAGE)
class PdfRenderApplication {
    companion object {
        // This app class lives under app.epistola.suite.pdfrender, but every bean and
        // @ConfigurationProperties it needs lives in epistola-core under app.epistola.suite.*.
        // Scan from the shared root so they are all picked up (as EpistolaSuiteApplication does
        // by virtue of sitting at the root package).
        const val BASE_PACKAGE = "app.epistola.suite"
    }
}

fun main(args: Array<String>) {
    LoggerFactory.getLogger(PdfRenderApplication::class.java).warn(
        "pdfrender is a BETA feature — functional but not yet supported for production. " +
            "Its deployment shape and the pdf-render capability/config may still change.",
    )
    runApplication<PdfRenderApplication>(*args)
}
