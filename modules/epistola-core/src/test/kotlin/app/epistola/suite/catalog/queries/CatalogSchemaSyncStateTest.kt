package app.epistola.suite.catalog.queries

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure derivation of the source-vs-app schema sync state. No Spring, no DB. */
class CatalogSchemaSyncStateTest {

    @Test
    fun `source below current is behind`() {
        assertThat(CatalogSchemaSyncState.of(sourceSchemaVersion = 2, currentSchemaVersion = 4))
            .isEqualTo(CatalogSchemaSyncState.SOURCE_BEHIND)
    }

    @Test
    fun `source equal to current is in sync`() {
        assertThat(CatalogSchemaSyncState.of(sourceSchemaVersion = 4, currentSchemaVersion = 4))
            .isEqualTo(CatalogSchemaSyncState.IN_SYNC)
    }

    @Test
    fun `source above current is ahead`() {
        assertThat(CatalogSchemaSyncState.of(sourceSchemaVersion = 5, currentSchemaVersion = 4))
            .isEqualTo(CatalogSchemaSyncState.SOURCE_AHEAD)
    }
}
