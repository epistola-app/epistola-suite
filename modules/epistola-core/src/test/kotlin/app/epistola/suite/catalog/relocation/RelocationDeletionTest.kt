// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.fonts.commands.DeleteFont
import app.epistola.suite.fonts.model.FontInUseException
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.model.Node
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Deleting a resource checks what uses it first. After a move, a published version keeps naming the
 * resource by the address it had then, and must still count as a use.
 */
class RelocationDeletionTest : RelocationTestSupport() {

    @Test
    fun `a moved font still used by a published version under its old address cannot be deleted`() {
        val tenant = tenantWith("Delete moved font in use")
        importFont(tenant, letters, "acme")
        val version = publishTemplate(tenant, letters, singleNodeModel(Node(id = "title", type = "text", styles = fontStyle("acme", letters.value))))
        move(tenant, address(CatalogResourceType.FONT, letters, "acme").movedTo(shared))

        assertThatThrownBy { withMediator { DeleteFont(FontId(FontKey.of("acme"), catalogId(tenant, shared))).execute() } }
            .isInstanceOf(FontInUseException::class.java)
        assertPreviewRenders(tenant, letters, version)
    }

    @Test
    fun `a moved font nothing uses any more can still be deleted`() {
        val tenant = tenantWith("Delete moved font unused")
        importFont(tenant, letters, "acme")
        move(tenant, address(CatalogResourceType.FONT, letters, "acme").movedTo(shared))

        assertThat(withMediator { DeleteFont(FontId(FontKey.of("acme"), catalogId(tenant, shared))).execute() }).isTrue()
    }
}
