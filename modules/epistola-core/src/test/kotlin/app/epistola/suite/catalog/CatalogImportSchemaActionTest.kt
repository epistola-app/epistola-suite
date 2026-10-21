// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.CatalogImportSchemaAction.Companion.decide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure decision for what a ZIP import does about an outdated catalog schema
 * version. No Spring, no DB. The migratable cases are only reachable once a real
 * migration chain exists, so this is their primary coverage. The decision is the
 * same for every import: catalogs have no type (ADR 0032).
 */
class CatalogImportSchemaActionTest {

    private val current = 4

    @Test
    fun `current-version import proceeds`() {
        assertThat(decide(sourceVersion = 4, migratedVersion = 4, current = current, confirmed = false))
            .isEqualTo(CatalogImportSchemaAction.IMPORT)
    }

    @Test
    fun `below current with no migration path is blocked, confirmed or not`() {
        // The migrator left it sub-current (transitional / below baseline).
        assertThat(decide(sourceVersion = 2, migratedVersion = 2, current = current, confirmed = false))
            .isEqualTo(CatalogImportSchemaAction.BLOCK_TOO_OLD)
        assertThat(decide(sourceVersion = 2, migratedVersion = 2, current = current, confirmed = true))
            .isEqualTo(CatalogImportSchemaAction.BLOCK_TOO_OLD)
    }

    @Test
    fun `below current but migratable asks for confirmation`() {
        // The migrator upgraded the payload to current (migration available).
        assertThat(decide(sourceVersion = 2, migratedVersion = 4, current = current, confirmed = false))
            .isEqualTo(CatalogImportSchemaAction.CONFIRM_MIGRATION)
    }

    @Test
    fun `migratable proceeds once confirmed, whichever catalog receives it`() {
        // A received release used to be blocked here, never migrated; it migrates like any import now.
        assertThat(decide(sourceVersion = 2, migratedVersion = 4, current = current, confirmed = true))
            .isEqualTo(CatalogImportSchemaAction.IMPORT)
    }
}
