package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Browser coverage for the global form-error slot (the shared
 * `epistola-web/form-error` fragment) — both fill paths that only exist in
 * the browser:
 *
 * - the **shaped** path: a handler reports an operation-level failure with a
 *   real error status + an OOB fragment (`HtmxDsl.globalFormError`); the
 *   `htmx:beforeSwap` gate in app-shell.js must let the shaped response swap
 *   (HTMX ignores error-status bodies by default);
 * - the **safety-net** path: an unhandled 4xx with an RFC 7807-style JSON
 *   body is routed into the issuing form's slot by the global
 *   `htmx:responseError` listener.
 *
 * The server-side shaped-response contract (status, HX-Reswap, OOB body) is
 * asserted deterministically in [app.epistola.suite.loadtest.LoadTestHandlerTest].
 */
class GlobalFormErrorUiTest : BasePlaywrightTest() {

    @Test
    fun `handler-reported global error appears in the form's slot via OOB swap`() {
        lateinit var tenant: Tenant
        lateinit var templateKey: String
        withMediator {
            tenant = createUiTenant()
            val catalogId = CatalogId.default(TenantId(tenant.id))
            val tKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(tKey, catalogId)
            CreateDocumentTemplate(id = templateId, name = "Load Test Template").execute()
            CreateVersion(variantId = VariantId(VariantKey.INITIAL, templateId)).execute()
            templateKey = tKey.value
        }

        gotoAndReady("/tenants/${tenant.id}/load-tests/new")

        // The slot ships in the form, hidden until an error arrives.
        assertThat(page.locator("#start-load-test-error")).isHidden()

        page.locator("#templateId").selectOption("default/$templateKey")
        page.htmxSettle() // cascading variant/version/environment fragment

        page.locator("#testData").fill("{}")

        // Neither a version nor an environment selected → StartLoadTest
        // rejects → handler responds with globalFormError (shaped 422 + OOB).
        page.locator("button:has-text('Start Load Test')").click()

        val slot = page.locator("#start-load-test-error")
        assertThat(slot).isVisible()
        assertThat(slot).containsText("Exactly one of versionId or environmentId must be set")
    }

    @Test
    fun `unhandled JSON error lands in the form's slot via the client safety net`() {
        lateinit var tenant: Tenant
        withMediator { tenant = createUiTenant() }

        gotoAndReady("/tenants/${tenant.id}/fonts/new")

        page.locator("#slug").fill("acme-sans")
        page.locator("#name").fill("Acme Sans")

        // No face file attached → FontHandler.upload returns an unshaped 400
        // with a JSON `error` body; the htmx:responseError safety net must
        // surface it in this form's slot (not the page banner).
        page.locator("button:has-text('Upload')").click()

        val slot = page.locator("#font-upload-error")
        assertThat(slot).isVisible()
        assertThat(slot).hasText("At least one face file is required")
    }

    private fun createUiTenant(): Tenant {
        val tenantKey = TenantKey.of("test-gfe-${System.nanoTime()}")
        return CreateTenant(id = tenantKey, name = "Global Form Error UI Test").execute()
    }
}
