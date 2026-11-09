// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/** `/tenants/{t}/support/mirror` with the support connection off (the default): nothing to opt in to, and it says why. */
class CatalogMirrorOffPageHtmxTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `with support off the page says so and where sharing is set, and the menu does not offer it`() {
        val tenant = createTenant("Mirror page off").id

        val response = restTemplate.getForEntity("/tenants/${tenant.value}/support/mirror", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("Support off")
        assertThat(body).doesNotContain("data-testid=\"mirror-opt-in\"")
        assertThat(body).contains("data-testid=\"mirror-where\"").contains("href=\"/tenants/${tenant.value}/support\"")
        assertThat(body).doesNotContain("href=\"/tenants/${tenant.value}/support/mirror\"")
    }
}
