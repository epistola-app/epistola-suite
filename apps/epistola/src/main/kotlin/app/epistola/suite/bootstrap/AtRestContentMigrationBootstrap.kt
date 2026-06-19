package app.epistola.suite.bootstrap

import app.epistola.suite.catalog.migrations.AtRestContentMigrator
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Boot-time pass that upgrades already-stored content blobs to the current
 * content schema version (see [AtRestContentMigrator]) — the at-rest companion
 * to the import-boundary wire migrator. Runs before the app serves traffic, so
 * nothing reads a stale-shaped blob.
 *
 * Ordered **before** [SystemCatalogBootstrap] (and the unordered `DemoLoader`):
 * the global content-version counter cannot distinguish a freshly-written
 * current blob from a lagging one, so the in-place migration must complete
 * before any bootstrap writes current-shape content. The pass is a cheap no-op
 * until the first real `CatalogSchemaMigration` ships.
 */
@Component
@Order(SystemCatalogBootstrap.RUN_ORDER - 50)
class AtRestContentMigrationBootstrap(
    private val migrator: AtRestContentMigrator,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        try {
            migrator.migrateAll()
        } catch (e: Exception) {
            log.error("At-rest content migration failed: {}", e.message, e)
        }
    }
}
