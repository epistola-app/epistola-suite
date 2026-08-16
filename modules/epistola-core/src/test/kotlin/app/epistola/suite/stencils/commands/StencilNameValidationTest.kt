// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.ValidationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Server-side guard for the stencil-picker stored XSS. `name` and `tags` are short
 * labels, rejected like the asset `name`; `description` is deliberately not rejected
 * (free text where '<' is legitimate) and relies on the client escape alone.
 */
class StencilNameValidationTest {

    private val stencilId = StencilId(StencilKey.of("teststencil"), CatalogId.default(TenantId(TenantKey.of("testtenant"))))

    @Test
    fun `create rejects a name containing markup`() {
        val thrown = assertFailsWith<ValidationException> {
            CreateStencil(id = stencilId, name = "\"><img src=x onerror=alert(1)>")
        }
        if (thrown.field != "name") fail("Expected 'name' rejection, got '${thrown.field}': ${thrown.message}")
    }

    @Test
    fun `create rejects a name containing a control character`() {
        val thrown = assertFailsWith<ValidationException> {
            CreateStencil(id = stencilId, name = "evil${Char(0)}")
        }
        if (thrown.field != "name") fail("Expected 'name' rejection, got '${thrown.field}': ${thrown.message}")
    }

    @Test
    fun `create rejects a tag containing markup`() {
        val thrown = assertFailsWith<ValidationException> {
            CreateStencil(id = stencilId, name = "Fine", tags = listOf("ok", "<img onerror=alert(1)>"))
        }
        if (thrown.field != "tags[1]") fail("Expected 'tags[1]' rejection, got '${thrown.field}': ${thrown.message}")
    }

    @Test
    fun `create accepts an ordinary name, tags, and a description containing angle brackets`() {
        val cmd = CreateStencil(
            id = stencilId,
            name = "Invoice Header",
            description = "Shows when a < b in the totals row",
            tags = listOf("billing", "header"),
        )
        assertEquals("Invoice Header", cmd.name)
        assertEquals("Shows when a < b in the totals row", cmd.description)
    }

    @Test
    fun `update rejects a name containing markup`() {
        val thrown = assertFailsWith<ValidationException> {
            UpdateStencil(id = stencilId, name = "<script>")
        }
        if (thrown.field != "name") fail("Expected 'name' rejection, got '${thrown.field}': ${thrown.message}")
    }

    @Test
    fun `update rejects a tag containing markup`() {
        val thrown = assertFailsWith<ValidationException> {
            UpdateStencil(id = stencilId, tags = listOf("<b>"))
        }
        if (thrown.field != "tags[0]") fail("Expected 'tags[0]' rejection, got '${thrown.field}': ${thrown.message}")
    }

    @Test
    fun `update accepts an ordinary name and a description containing angle brackets`() {
        val cmd = UpdateStencil(id = stencilId, name = "Renamed", description = "keeps a < b intact")
        assertEquals("Renamed", cmd.name)
        assertEquals("keeps a < b intact", cmd.description)
    }
}
