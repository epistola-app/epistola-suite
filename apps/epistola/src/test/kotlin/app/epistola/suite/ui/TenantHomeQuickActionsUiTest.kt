package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The tenant home "Quick Actions" buttons (New Template/Theme/Environment) must
 * open the create dialog directly — like the list pages' trigger — rather than
 * navigating to `…/new` (which is dialog-only and redirects to the list). They
 * load the dialog into #dialog-host with hx-boost="false"; a boosted navigation
 * to `?create` would not work because the deep-link reconcile only runs on full
 * page load / history restore, not on an in-app boosted swap.
 */
class TenantHomeQuickActionsUiTest : BasePlaywrightTest() {

    @Test
    fun `New Template quick action opens the create dialog from the home page`() {
        val tenant: Tenant = createTenant("Home Quick Actions")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("template-create-open-home"),
            "#create-template-dialog",
        )
        assertThat(dialog).isVisible()
        page.assertNativeModalOpen("#create-template-dialog")
    }
}
