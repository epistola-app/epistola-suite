// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common.ids

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Every slug-keyed entity must be creatable at its key's MAXIMUM allowed length.
 *
 * This guards a whole class of boundary bug: a slug that passes form/value-class
 * validation but then overflows a derived key or a `VARCHAR(n)` column further down
 * the create path, surfacing as a generic 500 instead of a clean result. The original
 * case (issue #632) was `CreateDocumentTemplate` deriving its default variant id as
 * `"{slug}-default"`, which pushed a 50-char template slug past `VariantKey`'s 50-char
 * limit. Existing tests only ever used short, auto-generated slugs, so nothing exercised
 * the limit — this test does, for every slug entity.
 *
 * The lengths below are the upper bound of each key's `require(value.length in ..)` in
 * [EntityKey]; an all-letter slug is a valid slug and never a reserved word.
 */
class MaxLengthSlugCreationTest : IntegrationTestBase() {

    @Test
    fun `every slug-keyed entity can be created at its maximum key length`() {
        val tenant = createTenant("Max Length")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)

        assertThatCode {
            withMediator {
                // Tenant — TenantKey max 63
                CreateTenant(id = TenantKey.of("t".repeat(63)), name = "Max Tenant").execute()

                // Catalog — CatalogKey max 50
                CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("c".repeat(50)), name = "Max Catalog").execute()

                // Theme — ThemeKey max 20
                CreateTheme(id = ThemeId(ThemeKey.of("h".repeat(20)), catalogId), name = "Max Theme").execute()

                // Template — TemplateKey max 50 (also creates its "initial" default variant)
                val templateId = TemplateId(TemplateKey.of("p".repeat(50)), catalogId)
                CreateDocumentTemplate(id = templateId, name = "Max Template").execute()

                // Variant — VariantKey max 50
                CreateVariant(
                    id = VariantId(VariantKey.of("v".repeat(50)), templateId),
                    title = "Max Variant",
                    description = null,
                ).execute()

                // Attribute — AttributeKey max 50
                CreateAttributeDefinition(
                    id = AttributeId(AttributeKey.of("a".repeat(50)), catalogId),
                    displayName = "Max Attribute",
                ).execute()

                // Code list — CodeListKey max 64
                CreateCodeList(
                    id = CodeListId(CodeListKey.of("l".repeat(64)), catalogId),
                    displayName = "Max Code List",
                    sourceType = CodeListSource.INLINE,
                    entries = listOf(CodeListEntry("en", "English")),
                ).execute()

                // Stencil — StencilKey max 50
                CreateStencil(id = StencilId(StencilKey.of("s".repeat(50)), catalogId), name = "Max Stencil").execute()

                // Environment — EnvironmentKey max 30
                CreateEnvironment(
                    id = EnvironmentId(EnvironmentKey.of("e".repeat(30)), tenantId),
                    name = "Max Environment",
                ).execute()

                // Font — FontKey max 64
                ImportFont(
                    tenantId = tenantId,
                    catalogKey = CatalogKey.DEFAULT,
                    slug = "f".repeat(64),
                    name = "Max Font",
                    kind = FontKind.SANS.wire,
                ).execute()
            }
        }.doesNotThrowAnyException()
    }
}
