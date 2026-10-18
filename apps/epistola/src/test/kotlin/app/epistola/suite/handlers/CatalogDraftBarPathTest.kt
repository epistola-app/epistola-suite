// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.htmx.CatalogSegment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The draft links of a content page: the same page, with only the catalog segment swapped. */
class CatalogDraftBarPathTest {

    private companion object {
        val WORK: java.util.UUID = java.util.UUID.fromString("0193a6b2-7f4e-7c1d-9a2b-3c4d5e6f7a8b")
    }

    @Test
    fun `only the catalog segment changes, even when the tenant has the catalog's name`() {
        val current = CatalogSegment.parse("letters")
        val pageAt = CatalogDraftBar.inPath("/tenants/letters/templates/letters/invoice/settings", current)

        assertThat(pageAt(current.at(WORK))).isEqualTo("/tenants/letters/templates/letters@$WORK/invoice/settings")
    }

    @Test
    fun `a draft's page links back to the current version plainly, keeping the query`() {
        val draft = CatalogSegment.parse("letters@$WORK")
        val pageAt = CatalogDraftBar.inPath("/tenants/acme/code-lists/letters@$WORK/colours", draft, "show=all")

        assertThat(pageAt(draft.at(null))).isEqualTo("/tenants/acme/code-lists/letters/colours?show=all")
    }
}
