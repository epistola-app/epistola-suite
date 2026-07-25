// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.validation.ValidationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * A null title is not covered here: [CreateVariant.title] and [UpdateVariant.title] are
 * non-null `String`s, so the compiler rejects it and there is no runtime case to assert.
 * These tests cover what the type system cannot — blank and over-length titles.
 */
class RequiredVariantTitleTest {

    private val templateId = TemplateId(TemplateKey("invoice"), CatalogId(CatalogKey.DEFAULT, TenantId(TenantKey("testtenant"))))
    private val variantId = VariantId(VariantKey("english"), templateId)

    @Test
    fun `CreateVariant rejects a blank title`() {
        val thrown = assertThrows<ValidationException> { CreateVariant(variantId, title = "   ", description = null) }
        assertEquals("title", thrown.field)
    }

    @Test
    fun `UpdateVariant rejects a blank title`() {
        val thrown = assertThrows<ValidationException> { UpdateVariant(variantId, title = "", attributes = emptyMap()) }
        assertEquals("title", thrown.field)
    }

    @Test
    fun `CreateVariant rejects an over-length title`() {
        val thrown = assertThrows<ValidationException> { CreateVariant(variantId, title = "x".repeat(101), description = null) }
        assertEquals("title", thrown.field)
    }

    @Test
    fun `a non-blank title is accepted`() {
        CreateVariant(variantId, title = "English", description = null)
        UpdateVariant(variantId, title = "English", attributes = emptyMap())
    }
}
