package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

class TemplateDeleteButtonUiTest : BasePlaywrightTest() {

    @Test
    fun `delete template button is visible in page header on template detail page`() {
        val (tenant, template) = withMediator {
            val tenantKey = TenantKey.of("test-ui-tenant-${System.nanoTime()}")
            val tenant = CreateTenant(id = tenantKey, name = "UI Test Tenant").execute()
            val tenantId = TenantId(tenant.id)
            val template = CreateDocumentTemplate(
                id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId)),
                name = "UI Test Template",
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")

        assertThat(page.locator(".page-header button:has-text('Delete Template')")).isVisible()
    }
}
