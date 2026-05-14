package app.epistola.suite.installation

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Ensures the installation identity row exists in `app_metadata` on every
 * boot. `@Order(0)` runs ahead of other [ApplicationRunner]s (e.g. DemoLoader)
 * so anything else that wants to read the installation can rely on it being
 * present.
 */
@Component
@Order(0)
class InstallationBootstrap(
    private val installations: InstallationService,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val installation = installations.getOrCreate()
        log.info(
            "Epistola installation id: {} (createdAt={})",
            installation.id,
            installation.createdAt,
        )
    }
}
