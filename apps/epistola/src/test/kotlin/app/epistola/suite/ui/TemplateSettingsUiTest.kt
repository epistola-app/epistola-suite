// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.commands.CreateEnvironment
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

/**
 * Browser coverage for the two settings-page interactions the hand-rolled-HTMX
 * conversion rewired in the browser (#477 groundwork):
 *
 * - the **compare trigger**: was a delegated click listener calling
 *   `htmx.ajax()` + `showModal()`; now `hx-get` + the app-wide
 *   `data-open-dialog` hook — two independent behaviors that must both fire
 *   from one click;
 * - the **rename Escape-revert**: was `dataset.originalName` bookkeeping; now
 *   the server-rendered `defaultValue`, which must track the LATEST committed
 *   name after a successful hx-patch swap.
 *
 * The server contracts behind these are pinned in
 * [app.epistola.suite.handlers.TemplateSettingsPatchHtmxTest].
 */
class TemplateSettingsUiTest : BasePlaywrightTest() {

    @Test
    fun `compare trigger opens the dialog and loads the comparison UI in one click`() {
        val (tenant, template) = withMediator { createPublishedTemplateWithDraft() }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/deployments")

        val trigger = page.locator("button[title='Compare versions']").first()
        val dialog = page.openDialogByTrigger(trigger, "#version-comparison-dialog")

        // The hx-get must have filled the dialog body with the comparison UI:
        // both version selects populated from the seeded versions.
        assertThat(dialog.locator("#compare-version-a option")).hasCount(2)
        assertThat(dialog.locator("#compare-version-b option")).hasCount(2)
        assertThat(dialog.locator("[data-compare-versions]")).isVisible()
    }

    @Test
    fun `escape reverts the rename input to the latest committed name`() {
        val (tenant, template) = withMediator { createPublishedTemplateWithDraft() }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/settings")

        val input = page.locator("[data-template-name-input]")

        // Commit a rename: Enter blurs, the change event hx-patches, the input
        // swaps back and the page header syncs via the OOB fragment.
        input.fill("Renamed In Browser")
        input.press("Enter")
        assertThat(page.locator("#page-title-text")).hasText("Renamed In Browser")

        // Escape must revert to the LATEST committed name (the swapped input's
        // defaultValue), not the page-load-time one — and issue no request.
        input.fill("garbage typing")
        input.press("Escape")
        assertThat(input).hasValue("Renamed In Browser")
        assertThat(page.locator("#page-title-text")).hasText("Renamed In Browser")
    }

    @Test
    fun `a failed toggle reverts to server truth instead of keeping the refused state`() {
        val (tenant, template) = withMediator { createPublishedTemplateWithDraft() }
        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/settings")

        // PDF/A defaults to enabled — that is the server truth to revert to.
        val toggle = page.locator("#pdfa-toggle")
        assertThat(toggle).isChecked()

        // Fail the PATCH at the network edge: data-revert-on-error must not
        // depend on response shape, and the safety net still says why.
        page.route("**/pdfa") { route -> route.abort() }
        toggle.click()

        // The click unchecks it locally; the failed request must snap it back
        // to the persisted state instead of leaving the refused value.
        assertThat(page.locator("#notices .notice")).isVisible()
        assertThat(toggle).isChecked()
    }

    private fun createPublishedTemplateWithDraft(): Pair<Tenant, DocumentTemplate> {
        val tenant = CreateTenant(
            id = TenantKey.of("settings-ui-${System.nanoTime()}"),
            name = "Settings UI Tenant",
        ).execute()
        val tenantId = TenantId(tenant.id)
        val template = CreateDocumentTemplate(
            id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId)),
            name = "Settings UI Template",
        ).execute()
        // The deployment matrix only renders rows when an environment exists.
        CreateEnvironment(
            id = EnvironmentId(EnvironmentKey.of("prod"), tenantId),
            name = "Production",
        ).execute()
        val variantId = VariantId(VariantKey.INITIAL, TemplateId(template.id, CatalogId.default(tenantId)))
        val draft = GetDraft(variantId).query()!!
        PublishVersion(versionId = VersionId(draft.id, variantId)).execute()
        CreateVersion(variantId = variantId).execute()
        return tenant to template
    }
}
