// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.handlers.CatalogReleaseReadiness.PublishedChange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The publish dialog's "What changes": grouped by kind, and summed up in one line. */
class PublishChangeGroupsTest {

    private fun change(kind: String, name: String, state: CatalogResourceState) = PublishedChange(kind, name, state)

    private val changes = listOf(
        change("Template", "Invoice", CatalogResourceState.NEW),
        change("Theme", "Brand", CatalogResourceState.MODIFIED),
        change("Template", "Reminder", CatalogResourceState.MODIFIED),
        change("Dependency", "Shared", CatalogResourceState.NEW),
        change("Catalog setting", "Default theme", CatalogResourceState.REMOVED),
    )

    @Test
    fun `changes are grouped by kind, in the order the kinds first appear, under their plural`() {
        val groups = CatalogReleaseReadiness.groupByKind(changes)

        assertThat(groups.map { it.heading }).containsExactly("Templates", "Themes", "Dependencies", "Catalog settings")
        assertThat(groups.first().changes.map { it.name }).containsExactly("Invoice", "Reminder")
    }

    @Test
    fun `the summary counts the changes and says of which sort`() {
        assertThat(CatalogReleaseReadiness.summarize(changes)).isEqualTo("5 changes: 2 added, 2 changed, 1 removed")
        assertThat(CatalogReleaseReadiness.summarize(changes.take(1))).isEqualTo("1 change: 1 added")
        assertThat(CatalogReleaseReadiness.summarize(emptyList())).isNull()
    }
}
