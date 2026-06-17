package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Catalogs converted from the old statically-rendered dialog + inline `onclick` +
 * OOB-list-swap to the shared create-dialog model: the "New Catalog" trigger loads
 * the dialog into #dialog-host, a successful create HX-Redirects to the new
 * catalog's browse page (by slug), and `?create` deep-links the open dialog. A wide
 * viewport keeps the sticky nav off the top-right action button's click point.
 */
class CatalogCreateDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `create catalog via dialog navigates to the new catalog browse page`() {
        val tenant: Tenant = createTenant("Catalog Dialog Test")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/catalogs")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("catalog-create-open"),
            "#create-catalog-dialog",
        )
        assertThat(dialog).isVisible()

        page.locator("#create-catalog-form #catalogName").fill("Marketing Catalog")
        page.locator("#create-catalog-form #catalogSlug").fill("marketing")
        page.getByTestId("catalog-create-submit").click()

        // Success HX-Redirects to the new catalog's browse page, by slug.
        assertThat(page).hasURL(Pattern.compile(".*/catalogs/marketing/browse$"))
    }

    @Test
    fun `deep link to create opens the dialog`() {
        val tenant: Tenant = createTenant("Catalog Dialog Deeplink")

        // Landing directly on ?create proves the converted page ships the dialog
        // markup and the shared reconcile promotes it into a real top-layer modal.
        gotoAndReady("/tenants/${tenant.id}/catalogs?create")

        page.assertNativeModalOpen("#create-catalog-dialog")
    }
}
