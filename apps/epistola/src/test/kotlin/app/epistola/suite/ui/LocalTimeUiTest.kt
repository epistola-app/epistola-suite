// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.Browser
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Times are written by the server in UTC and shown in the reader's time zone by `behaviors.js`
 * (`data-local-time`), with the UTC time kept in the title. Tokyo has no daylight saving, so the
 * expected text does not depend on the date.
 */
class LocalTimeUiTest : BasePlaywrightTest() {

    override fun contextOptions(): Browser.NewContextOptions = Browser.NewContextOptions().setTimezoneId(TOKYO)

    @Test
    fun `a time reads in the browser's time zone, with UTC in the title`() {
        val tenantKey = TenantKey.of("local-time-${System.nanoTime()}")
        val environment = withMediator {
            CreateTenant(tenantKey, "Local Time UI Test").execute()
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("staging"), TenantId(tenantKey)), "Staging").execute()
        }
        val utc = environment.createdAt.atZoneSameInstant(ZoneId.of("UTC")).format(FORMAT)
        val tokyo = environment.createdAt.atZoneSameInstant(ZoneId.of(TOKYO)).format(FORMAT)

        gotoAndReady("/tenants/${tenantKey.value}/environments")

        val created = page.locator("[data-local-time]").first()
        assertThat(created).hasText(tokyo)
        assertThat(created).hasAttribute("title", "$utc UTC")
    }

    private companion object {
        const val TOKYO = "Asia/Tokyo"
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
