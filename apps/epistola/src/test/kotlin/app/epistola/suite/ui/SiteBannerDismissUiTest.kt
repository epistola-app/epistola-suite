package app.epistola.suite.ui

import app.epistola.suite.banner.SiteBannerSeverity
import app.epistola.suite.banner.commands.ClearSiteBanner
import app.epistola.suite.banner.commands.SetSiteBanner
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Browser coverage for the site-banner dismissal, which is browser-only logic:
 * dismissing hides it for the session (sessionStorage), a reload keeps it hidden,
 * and an edited banner (new content hash) re-appears despite the prior dismissal.
 *
 * The banner is installation-wide, so the test restores a clean state afterwards.
 */
class SiteBannerDismissUiTest : BasePlaywrightTest() {

    @Test
    fun `banner is dismissible per session and reappears when edited`() {
        lateinit var tenant: Tenant
        withMediator {
            tenant = CreateTenant(TenantKey.of("test-banner-${System.nanoTime()}"), "Site Banner UI Test").execute()
            SetSiteBanner("Scheduled maintenance tonight", SiteBannerSeverity.WARNING, enabled = true).execute()
        }
        try {
            val banner = page.locator("[data-site-banner]")

            gotoAndReady("/tenants/${tenant.id}/features")
            assertThat(banner).isVisible()

            // Dismiss → hidden for this session. Dispatch the click event directly:
            // the handler is a delegated document listener, and the button sits under
            // the sticky nav, so a positional click is occluded in the harness.
            page.locator("[data-dismiss-banner]").dispatchEvent("click")
            assertThat(banner).isHidden()

            // Reload: still hidden (sessionStorage remembers the dismissed hash).
            gotoAndReady("/tenants/${tenant.id}/features")
            assertThat(banner).isHidden()

            // Edit the banner (new content → new hash): it reappears despite the prior dismissal.
            withMediator {
                SetSiteBanner("Maintenance extended — data may be affected", SiteBannerSeverity.ERROR, enabled = true).execute()
            }
            gotoAndReady("/tenants/${tenant.id}/features")
            assertThat(banner).isVisible()
        } finally {
            withMediator { ClearSiteBanner().execute() }
        }
    }
}
