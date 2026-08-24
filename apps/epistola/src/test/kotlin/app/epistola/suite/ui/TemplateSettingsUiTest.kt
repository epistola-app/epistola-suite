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
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Browser coverage for the settings-page behaviors that only exist once the
 * page is live — each one is two mechanisms that have to line up:
 *
 * - the **compare trigger**: `hx-get` loads the comparison UI and the app-wide
 *   `data-open-dialog` hook opens the dialog — two independent behaviors that
 *   must both fire from one click;
 * - the **rename Escape-revert**: Escape restores the input's `defaultValue`,
 *   which must be the LATEST committed name — every successful hx-patch swap
 *   re-renders the input, so its value attribute tracks the server;
 * - the **failure revert**: HTMX does not swap on an error response, so
 *   `data-revert-on-error` must put the persisted value back — otherwise the
 *   control shows a value the server never accepted.
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
    fun `a failed rename reverts the input to the persisted name`() {
        val (tenant, template) = withMediator { createPublishedTemplateWithDraft() }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/settings")
        failEveryRequestTo("**/templates/**/name")

        val input = page.locator("[data-template-name-input]")
        input.fill("Never Persisted")
        input.press("Enter")

        // HTMX does not swap on an error response, so without the revert hook
        // the rejected name would sit in the field as if it had been saved.
        assertThat(input).hasValue("Settings UI Template")
        assertThat(page.locator("#page-title-text")).hasText("Settings UI Template")
    }

    @Test
    fun `a failed PDF-A toggle reverts the checkbox`() {
        val (tenant, template) = withMediator {
            val seeded = createPublishedTemplateWithDraft()
            // Pin the starting state instead of leaning on the create-time
            // default: the revert has to restore whatever the server holds.
            UpdateDocumentTemplate(
                id = TemplateId(seeded.second.id, CatalogId.default(TenantId(seeded.first.id))),
                pdfaEnabled = false,
            ).execute()
            seeded
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/settings")
        failEveryRequestTo("**/templates/**/pdfa")

        val toggle = page.locator("#pdfa-toggle")
        assertThat(toggle).not().isChecked()

        // click(), not check(): check() asserts the box stays checked afterwards,
        // which is precisely what the revert must undo.
        toggle.click()

        assertThat(toggle).not().isChecked()
    }

    @Test
    fun `a theme deleted after page load shows an error and the selector reverts`() {
        val (tenant, template, staleTheme) = withMediator {
            val seeded = createPublishedTemplateWithDraft()
            val tenantId = TenantId(seeded.first.id)
            val catalogId = CatalogId.default(tenantId)
            val currentTheme = ThemeId(ThemeKey.of("current-theme"), catalogId)
            val staleTheme = ThemeId(ThemeKey.of("stale-theme"), catalogId)
            CreateTheme(id = currentTheme, name = "Current Theme").execute()
            CreateTheme(id = staleTheme, name = "Soon Deleted Theme").execute()
            UpdateDocumentTemplate(
                id = TemplateId(seeded.second.id, catalogId),
                themeId = currentTheme.key,
                themeCatalogKey = currentTheme.catalogKey,
            ).execute()
            Triple(seeded.first, seeded.second, staleTheme)
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}/settings")
        val select = page.locator("#theme-select")
        assertThat(select).hasValue("default/current-theme")

        // The page still offers this option, but another user/tab removes it
        // before the selection reaches the server.
        withMediator { DeleteTheme(staleTheme).execute() }
        select.selectOption("default/stale-theme")

        assertThat(select).hasValue("default/current-theme")
        val error = page.locator("#theme-select-error")
        assertThat(error).isVisible()
        assertThat(error).containsText("The selected theme 'default/stale-theme' no longer exists")
    }

    /** Makes the endpoint answer 500 so the control's error path runs. */
    private fun failEveryRequestTo(urlPattern: String) {
        page.route(urlPattern) { route ->
            route.fulfill(
                Route.FulfillOptions()
                    .setStatus(500)
                    .setContentType("text/plain")
                    .setBody("forced failure"),
            )
        }
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
        ).execute().withRequiredDataExample()
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
