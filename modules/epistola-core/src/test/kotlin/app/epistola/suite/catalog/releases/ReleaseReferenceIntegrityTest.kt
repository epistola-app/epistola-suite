// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeleteCatalogRelease
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * What is released must render: a release never pins a release the tenant does not have (the
 * `pinned-release-present` catalog invariant). A draft may still pin one, since deleting a release
 * is refused only while another release pins it; releasing that draft is where it ends.
 */
class ReleaseReferenceIntegrityTest : IntegrationTestBase() {

    @Test
    fun `a draft pinning a release that was deleted is not released`() {
        val tenant = createTenant("deleted-pin").id
        val shared = catalogIn(tenant, "shared")
        val letters = catalogIn(tenant, "letters")
        val templateId = TemplateId(TemplateKey.of("invoice"), letters)
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), shared), "Brand").execute()
            mediator.releaseNext(shared)
            declareDependency(tenant, letters.key, on = shared.key)
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(tenant, shared.key)).execute()
            UpdateVariantContent(VariantId(VariantKey.INITIAL, templateId), TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            mediator.publishAndRelease(VariantId(VariantKey.INITIAL, templateId))
            DeleteCatalogRelease(letters, "1.0.0").execute()
            DeleteCatalogRelease(shared, "1.0.0").execute()
            UpdateVariantContent(VariantId(VariantKey.INITIAL, templateId), TestTemplateBuilder.buildMinimal(name = "Invoice 2")).execute()
        }

        assertThatThrownBy { withMediator { mediator.publishAndRelease(VariantId(VariantKey.INITIAL, templateId)) } }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("pins 1.0.0, which this tenant no longer has")
    }

    private fun catalogIn(tenant: TenantKey, slug: String): CatalogId {
        val key = CatalogKey.of(slug)
        withMediator { CreateCatalog(tenantKey = tenant, id = key, name = slug).execute() }
        return testCatalog(TenantId(tenant), key)
    }
}
