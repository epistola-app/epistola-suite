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
 * Deleting a resource checks what uses it at its current address. After a move, a published version
 * keeps naming the resource by the address it had then; nothing is left there, so that reference is
 * already broken and does not count as a use.
 */
class RelocationDeletionTest : RelocationTestSupport() {

    @Test
    fun `a moved font named only by its old address can be deleted`() {
        val tenant = tenantWith("Delete moved font named by old address")
        importFont(tenant, letters, "acme")
        publishTemplate(tenant, letters, singleNodeModel(Node(id = "title", type = "text", styles = fontStyle("acme", letters.value))))
        move(tenant, address(CatalogResourceType.FONT, letters, "acme").movedTo(shared))

        assertThat(withMediator { DeleteFont(FontId(FontKey.of("acme"), catalogId(tenant, shared))).execute() }).isTrue()
    }

    @Test
    fun `a moved font used at its new address cannot be deleted`() {
        val tenant = tenantWith("Delete moved font in use")
        importFont(tenant, letters, "acme")
        move(tenant, address(CatalogResourceType.FONT, letters, "acme").movedTo(shared))
        publishTemplate(tenant, letters, singleNodeModel(Node(id = "title", type = "text", styles = fontStyle("acme", shared.value))))

        assertThatThrownBy { withMediator { DeleteFont(FontId(FontKey.of("acme"), catalogId(tenant, shared))).execute() } }
            .isInstanceOf(FontInUseException::class.java)
    }

    @Test
    fun `a moved font nothing uses any more can still be deleted`() {
        val tenant = tenantWith("Delete moved font unused")
        importFont(tenant, letters, "acme")
        move(tenant, address(CatalogResourceType.FONT, letters, "acme").movedTo(shared))

        assertThat(withMediator { DeleteFont(FontId(FontKey.of("acme"), catalogId(tenant, shared))).execute() }).isTrue()
    }
}
