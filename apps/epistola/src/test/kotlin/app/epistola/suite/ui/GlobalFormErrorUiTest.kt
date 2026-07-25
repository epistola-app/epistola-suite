// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
 * Browser coverage for the shared `epistola-web/form-error` fragment's shaped
 * swap paths (the fill mechanics that only exist in the browser):
 *
 * - the **global-slot** path: a handler reports an operation-level failure with
 *   a real error status + an OOB fragment (`HtmxDsl.globalFormError`); the
 *   `htmx:beforeSwap` gate in app-shell.js must let the shaped response swap
 *   (HTMX ignores error-status bodies by default);
 * - the **per-field OOB** path (upload family): a multipart upload reports a
 *   validation error that OOB-swaps just the `field-error` span
 *   (`HtmxDsl.dialogFieldErrorsOob`), leaving the form body — and the user's
 *   file selection — untouched.
 *
 * The server-side shaped-response contract (status, HX-Reswap, OOB body) is
 * asserted deterministically in [app.epistola.suite.loadtest.LoadTestHandlerTest]
 * (global slot) and [app.epistola.suite.fonts.FontDialogHandlerHtmxTest]
 * (per-field). The client safety-net path (an UNSHAPED 4xx routed into a slot by
 * `htmx:responseError`) is not exercised here — both uploads now return shaped
 * responses; re-home it if a browser-reachable unshaped 4xx form is added.
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
    fun `upload validation error appears inline in the dialog's field span via OOB swap`() {
        lateinit var tenant: Tenant
        withMediator { tenant = createUiTenant() }

        // Direct navigation renders the fonts list with the upload dialog open.
        gotoAndReady("/tenants/${tenant.id}/fonts/new")

        page.locator("#slug").fill("acme-sans")
        page.locator("#name").fill("Acme Sans")

        // No face file attached → FontHandler.upload returns a SHAPED 422 that
        // OOB-swaps the aggregate faces field span. The upload family never
        // re-renders the form body (file inputs can't survive a round-trip), so
        // the message lands in the field span and the dialog stays open.
        // (Target the dialog's submit by testid — the list's "Upload Font"
        // trigger also matches an Upload text locator.)
        page.locator("[data-testid='create-form-submit']").click()

        val slot = page.locator("#font-faces-error")
        assertThat(slot).isVisible()
        assertThat(slot).hasText("At least one face file is required")
    }

    private fun createUiTenant(): Tenant {
        val tenantKey = TenantKey.of("test-gfe-${System.nanoTime()}")
        return CreateTenant(id = tenantKey, name = "Global Form Error UI Test").execute()
    }
}
