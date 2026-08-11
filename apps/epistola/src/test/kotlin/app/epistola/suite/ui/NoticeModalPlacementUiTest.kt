// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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

/**
 * Browser-only half of the corner-notice coverage (#477): the notices-above-
 * modal-dialogs placement in notices.js needs the real top layer (popover API,
 * dialog ToggleEvents), which the happy-dom unit suite
 * (apps/epistola/src/test/js/notices.test.js) cannot fake — its tests cover
 * everything else.
 *
 * Uses the shell's changelog dialog as the modal host and fires notices via
 * the public epistolaNotice API — the same call sites like pdf-preview.js use.
 */
class NoticeModalPlacementUiTest : BasePlaywrightTest() {

    @Test
    fun `a notice fired while a modal dialog is open paints above it`() {
        val tenant = createTenant("Notice Hoist")
        gotoAndReady("/tenants/${tenant.id}/environments")
        page.openDialogByTrigger(page.locator("[data-open-dialog='changelog-dialog']"), "#changelog-dialog")

        page.evaluate("() => window.epistolaNotice.error('Hoisted over the dialog.')")

        // The region is hosted INSIDE the open dialog as a manual popover —
        // re-entering the top layer above the dialog and its backdrop.
        val hoisted = page.locator("#changelog-dialog #notices[popover='manual'] .notice")
        assertThat(hoisted).isVisible()
        assertThat(hoisted).containsText("Hoisted over the dialog.")
    }

    @Test
    fun `closing the dialog returns the region home with the notice intact`() {
        val tenant = createTenant("Notice Return Home")
        gotoAndReady("/tenants/${tenant.id}/environments")
        page.openDialogByTrigger(page.locator("[data-open-dialog='changelog-dialog']"), "#changelog-dialog")
        page.evaluate("() => window.epistolaNotice.success('Still here after close.')")
        assertThat(page.locator("#changelog-dialog #notices .notice")).isVisible()

        page.keyboard().press("Escape")

        // Back to its normal fixed placement: direct body child, popover gone.
        val home = page.locator("body > #notices:not([popover]) .notice")
        assertThat(home).isVisible()
        assertThat(home).containsText("Still here after close.")
    }

    @Test
    fun `a notice survives its host dialog being removed outright`() {
        val tenant = createTenant("Notice Rescue")
        gotoAndReady("/tenants/${tenant.id}/environments")
        page.openDialogByTrigger(page.locator("[data-open-dialog='changelog-dialog']"), "#changelog-dialog")
        page.evaluate("() => window.epistolaNotice.error('Survives the unmount.')")
        assertThat(page.locator("#changelog-dialog #notices .notice")).isVisible()

        // A framework-rendered dialog (the editor's Lit dialogs) unmounts by
        // direct DOM removal — no close(), no toggle event. The MutationObserver
        // rescue must re-home the region, live notice intact.
        page.evaluate("() => document.getElementById('changelog-dialog').remove()")

        val rescued = page.locator("body > #notices:not([popover]) .notice")
        assertThat(rescued).isVisible()
        assertThat(rescued).containsText("Survives the unmount.")

        // The rescued notice is still fully functional — dismiss removes it.
        rescued.locator("[data-notice-dismiss]").click()
        assertThat(page.locator("#notices .notice")).hasCount(0)
    }

    @Test
    fun `the region follows the top of a modal stack and unwinds with it`() {
        val tenant = createTenant("Notice Stack")
        gotoAndReady("/tenants/${tenant.id}/environments")
        page.openDialogByTrigger(page.locator("[data-open-dialog='changelog-dialog']"), "#changelog-dialog")
        page.evaluate("() => window.epistolaNotice.error('Follows the stack.')")
        assertThat(page.locator("#changelog-dialog #notices .notice")).isVisible()

        // A second modal stacks on top — the shared confirm dialog, which is the
        // documented real pairing (confirm over another dialog: opening order,
        // not DOM order, decides the top). The region follows the top.
        page.evaluate("() => document.getElementById('confirm-dialog').showModal()")
        assertThat(page.locator("#confirm-dialog #notices[popover='manual'] .notice")).isVisible()

        // Closing the top modal hands the region to the dialog underneath…
        page.keyboard().press("Escape")
        assertThat(page.locator("#changelog-dialog #notices .notice")).isVisible()

        // …and closing that one sends it home.
        page.keyboard().press("Escape")
        assertThat(page.locator("body > #notices:not([popover]) .notice")).isVisible()
    }

    @Test
    fun `a server-sent notice lands in the hoisted region while a dialog is open`() {
        val (tenant, template) = withMediator {
            val tenant = CreateTenant(
                id = TenantKey.of("notice-oob-${System.nanoTime()}"),
                name = "Notice OOB Tenant",
            ).execute()
            val template = CreateDocumentTemplate(
                id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id))),
                name = "Notice OOB Template",
            ).execute()
            tenant to template
        }
        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")
        page.openDialogByTrigger(page.locator("[data-open-dialog='changelog-dialog']"), "#changelog-dialog")

        // A real server round-trip: the rename endpoint answers with a
        // successNotice OOB fragment (afterbegin:#notices). The region moved
        // into the dialog but travels with its id — the OOB lands inside it.
        val renameUrl = "/tenants/${tenant.id}/templates/default/${template.id}/name"
        page.evaluate(
            "url => htmx.ajax('PATCH', url, { values: { name: 'Renamed while hoisted' }, swap: 'none' })",
            renameUrl,
        )
        page.htmxSettle()

        val hoisted = page.locator("#changelog-dialog #notices .notice")
        assertThat(hoisted).isVisible()
        assertThat(hoisted).containsText("Template renamed.")
    }
}
