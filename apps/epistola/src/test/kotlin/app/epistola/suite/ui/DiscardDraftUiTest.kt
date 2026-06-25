package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

class DiscardDraftUiTest : BasePlaywrightTest() {

    @Test
    fun `discard draft reverts the variant to its published version`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createPublishedTemplateWithDraft()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")

        // A draft is open on top of a published version: both the Draft badge
        // and the Discard button are present.
        assertThat(page.locator(".badge-draft")).hasCount(1)
        val discardButton = page.locator(".variant-card button[title='Discard draft']")
        assertThat(discardButton).isVisible()

        // hx-confirm routes through the styled #confirm-dialog.
        val confirm = page.openDialogByTrigger(discardButton, "#confirm-dialog")
        confirm.locator("[data-testid='confirm-dialog-confirm']").click()

        // The handler returns HX-Redirect; the page reloads with the draft gone.
        assertThat(page.locator(".badge-draft")).hasCount(0)
        assertThat(page.locator(".badge-published")).hasCount(1)
        assertThat(page.locator(".variant-card button[title='Discard draft']")).hasCount(0)
    }

    @Test
    fun `discard button is absent when the variant was never published`() {
        // A fresh template's default variant has a draft but no published
        // version — there is nothing to revert to, so no Discard button.
        val (tenant, template) = withMediator {
            val tenantKey = TenantKey.of("discard-ui-${System.nanoTime()}")
            val tenant = CreateTenant(id = tenantKey, name = "Discard UI Tenant").execute()
            val template = CreateDocumentTemplate(
                id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id))),
                name = "Discard UI Template",
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")

        assertThat(page.locator(".badge-draft")).hasCount(1)
        assertThat(page.locator(".variant-card button[title='Discard draft']")).hasCount(0)
    }

    /**
     * Creates a tenant + template whose default variant has a published v1 and a
     * fresh draft v2 open on top of it.
     */
    private fun createPublishedTemplateWithDraft(): Pair<Tenant, DocumentTemplate> {
        val tenantKey = TenantKey.of("discard-ui-${System.nanoTime()}")
        val tenant = CreateTenant(id = tenantKey, name = "Discard UI Tenant").execute()
        val tenantId = TenantId(tenant.id)
        val template = CreateDocumentTemplate(
            id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId)),
            name = "Discard UI Template",
        ).execute()
        val variantId = VariantId(VariantKey.of("initial"), TemplateId(template.id, CatalogId.default(tenantId)))
        val draft = GetDraft(variantId).query()!!
        PublishVersion(versionId = VersionId(draft.id, variantId)).execute()
        CreateVersion(variantId = variantId).execute()
        return tenant to template
    }
}
