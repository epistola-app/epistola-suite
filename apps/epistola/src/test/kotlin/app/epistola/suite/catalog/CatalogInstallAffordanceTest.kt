// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * "Install catalog" appears only while something is left to install.
 *
 * It used to be gated on the catalog being subscribed, which is always true of a subscribed
 * catalog — so a fully installed one still offered to install itself, and the dialog it opened had
 * nothing to list. Upgrading to a newer release is a different action with its own button.
 */
class CatalogInstallAffordanceTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a fully installed subscribed catalog does not offer to install itself`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Install Affordance") } }

        // Every tenant is provisioned with the bundled `system` catalog already installed, which is
        // exactly the state that was offering the button.
        val body = restTemplate
            .getForEntity("/tenants/${tenant.id.value}/catalogs/${SYSTEM_CATALOG_KEY.value}/browse", String::class.java)
            .body!!

        assertThat(body).contains("Installed")
        assertThat(body)
            .`as`("nothing is left to install, so the action has nothing to do")
            .doesNotContain("Install catalog")
    }
}
