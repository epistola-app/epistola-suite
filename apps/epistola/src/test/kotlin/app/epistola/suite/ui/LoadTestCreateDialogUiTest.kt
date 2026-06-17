package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Load-test create dialog. The interesting bit is that the form's cascading
 * HTMX selects (template → variant/version/environment) must keep working when
 * the form lives inside the injected dialog — selecting a template swaps the
 * populated options into #template-options-section.
 */
class LoadTestCreateDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `selecting a template populates the variant dropdown inside the dialog`() {
        lateinit var templateKey: String
        val tenant: Tenant = withMediator {
            val t = CreateTenant(id = TenantKey.of("lt-dialog-${System.nanoTime()}"), name = "Load Test Dialog").execute()
            val tpl = CreateDocumentTemplate(
                id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(t.id))),
                name = "Invoice Template",
            ).execute()
            templateKey = tpl.id.value
            t
        }

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/load-tests")

        page.openDialogByTrigger(
            page.getByTestId("load-test-create-open"),
            "#create-load-test-dialog",
        )

        // Before selecting a template the variant dropdown is the disabled placeholder.
        assertThat(page.locator("#create-load-test-dialog #variantId")).isDisabled()

        // Selecting a template triggers the cascade hx-get into #template-options-section.
        page.locator("#create-load-test-dialog #templateId")
            .selectOption("default/$templateKey")

        // The populated variant select is now enabled and lives inside the dialog.
        assertThat(page.locator("#create-load-test-dialog #variantId")).isEnabled()
    }

    @Test
    fun `deep link to create opens the dialog`() {
        val tenant: Tenant = createTenant("Load Test Deeplink")

        // Deep-linkable open only — `?create` opens the (empty-cascade) dialog as a
        // real top-layer modal. The cascade selections are intentionally NOT in the URL.
        gotoAndReady("/tenants/${tenant.id}/load-tests?create")

        page.assertNativeModalOpen("#create-load-test-dialog")
    }
}
