// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus

/**
 * Guards the trap that demo landing walked into once already.
 *
 * The tenant list **is** `GET /` — there is no `GET /tenants` — and "Switch tenant" in the nav,
 * "Back to tenants" in the platform banner and the error pages' "Back to Home" all point at it. An
 * earlier attempt sent demo users to their own tenant by overriding that route, which made the
 * switcher a no-op and put the shared `demo` tenant out of reach through the UI — the very tenant
 * [DemoLoginMembershipResolver] grants everyone read/write on.
 *
 * Landing is now a post-login decision ([DemoPostLoginTarget]), so `/` stays the list. If a future
 * change moves it back onto the route, this fails.
 */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    app.epistola.suite.config.TestSecurityContextConfiguration::class,
)
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=true",
        "epistola.generation.polling.enabled=false",
    ],
)
@AutoConfigureTestRestTemplate
class DemoTenantListReachableIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the tenant switcher still reaches the tenant list in demo mode`() {
        val response = restTemplate.getForEntity("/", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("<title>Tenants")
    }

    @Test
    fun `the shared demo tenant is reachable`() {
        // DemoLoader seeds it at boot; every demo user is granted read/write on it, so it has to be
        // openable — which, with the list intact, it is.
        val response = restTemplate.getForEntity("/tenants/${DemoLoader.DEMO_TENANT_ID}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }
}
