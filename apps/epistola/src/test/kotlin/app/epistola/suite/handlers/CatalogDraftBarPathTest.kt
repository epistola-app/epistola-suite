// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.htmx.CatalogSegment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The draft links of a content page: the same page, with only the catalog segment swapped. */
class CatalogDraftBarPathTest {

    @Test
    fun `only the catalog segment changes, even when the tenant has the catalog's name`() {
        val current = CatalogSegment.parse("letters")
        val pageAt = CatalogDraftBar.inPath("/tenants/letters/templates/letters/invoice/settings", current)

        assertThat(pageAt(current.at("1.0.1"))).isEqualTo("/tenants/letters/templates/letters@1.0.1/invoice/settings")
    }

    @Test
    fun `a draft's page links back to the current version plainly, keeping the query`() {
        val draft = CatalogSegment.parse("letters@1.0.1")
        val pageAt = CatalogDraftBar.inPath("/tenants/acme/code-lists/letters@1.0.1/colours", draft, "show=all")

        assertThat(pageAt(draft.at(null))).isEqualTo("/tenants/acme/code-lists/letters/colours?show=all")
    }
}
