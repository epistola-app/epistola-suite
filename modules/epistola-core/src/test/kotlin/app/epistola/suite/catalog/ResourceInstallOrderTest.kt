// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in the install-order invariants that the ZIP import pipeline relies on.
 * `ImportCatalogZip` collects renumber decisions from the stencil install pass
 * and hands them to `ImportTemplates`, so stencils MUST install before
 * templates. Likewise, attributes can bind to code lists (FK enforced) so the
 * code list row has to exist first, and asset-backed font variants FK into
 * `assets` so assets must install before fonts.
 */
class ResourceInstallOrderTest {

    @Test
    fun `every resource type has an explicit ordering`() {
        val types = setOf("asset", "codeList", "font", "attribute", "theme", "stencil", "template")
        assertThat(RESOURCE_INSTALL_ORDER.keys).containsExactlyInAnyOrderElementsOf(types)
    }

    @Test
    fun `stencils install before templates so renumber map is populated`() {
        // ImportCatalogZip fills `stencilRenumbers` as stencils install and
        // passes the map to ImportTemplates. If templates ran first the map
        // would still be empty and the RENUMBER-mode pin rewrite would silently
        // miss the templates riding the same ZIP.
        assertThat(RESOURCE_INSTALL_ORDER.getValue("stencil"))
            .isLessThan(RESOURCE_INSTALL_ORDER.getValue("template"))
    }

    @Test
    fun `code lists install before attributes (FK attr_code_list_fk)`() {
        assertThat(RESOURCE_INSTALL_ORDER.getValue("codeList"))
            .isLessThan(RESOURCE_INSTALL_ORDER.getValue("attribute"))
    }

    @Test
    fun `assets install before fonts (FK font_variants asset_key)`() {
        assertThat(RESOURCE_INSTALL_ORDER.getValue("asset"))
            .isLessThan(RESOURCE_INSTALL_ORDER.getValue("font"))
    }
}
