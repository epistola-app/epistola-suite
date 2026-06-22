package app.epistola.suite.catalog

import app.epistola.suite.catalog.CatalogImportSchemaAction.Companion.decide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure decision for what a ZIP import does about an outdated catalog schema
 * version. No Spring, no DB. The migratable-AUTHORED cases are only reachable
 * once a real migration chain exists, so this is their primary coverage.
 */
class CatalogImportSchemaActionTest {

    private val current = 4

    @Test
    fun `current-version import proceeds for either type`() {
        assertThat(decide(CatalogType.AUTHORED, sourceVersion = 4, migratedVersion = 4, current = current, confirmed = false))
            .isEqualTo(CatalogImportSchemaAction.IMPORT)
        assertThat(decide(CatalogType.SUBSCRIBED, sourceVersion = 4, migratedVersion = 4, current = current, confirmed = false))
            .isEqualTo(CatalogImportSchemaAction.IMPORT)
    }

    @Test
    fun `SUBSCRIBED below current is always blocked - never migrated`() {
        // Even when a chain could upgrade it (migratedVersion == current).
        assertThat(decide(CatalogType.SUBSCRIBED, sourceVersion = 2, migratedVersion = 4, current = current, confirmed = false))
            .isEqualTo(CatalogImportSchemaAction.BLOCK_TOO_OLD)
        assertThat(decide(CatalogType.SUBSCRIBED, sourceVersion = 2, migratedVersion = 2, current = current, confirmed = true))
            .isEqualTo(CatalogImportSchemaAction.BLOCK_TOO_OLD)
    }

    @Test
    fun `AUTHORED below current with no migration path is blocked`() {
        // The migrator left it sub-current (transitional / below baseline).
        assertThat(decide(CatalogType.AUTHORED, sourceVersion = 2, migratedVersion = 2, current = current, confirmed = false))
            .isEqualTo(CatalogImportSchemaAction.BLOCK_TOO_OLD)
    }

    @Test
    fun `AUTHORED below current but migratable asks for confirmation`() {
        // The migrator upgraded the payload to current (migration available).
        assertThat(decide(CatalogType.AUTHORED, sourceVersion = 2, migratedVersion = 4, current = current, confirmed = false))
            .isEqualTo(CatalogImportSchemaAction.CONFIRM_MIGRATION)
    }

    @Test
    fun `AUTHORED migratable proceeds once confirmed`() {
        assertThat(decide(CatalogType.AUTHORED, sourceVersion = 2, migratedVersion = 4, current = current, confirmed = true))
            .isEqualTo(CatalogImportSchemaAction.IMPORT)
    }
}
