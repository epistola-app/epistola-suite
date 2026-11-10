// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * Deleting a catalog needs `CATALOG_MANAGE` ([app.epistola.suite.catalog.commands.DeleteCatalog]), so
 * the catalogs list offers its Delete control to whoever holds it, and to nobody else.
 */
class CatalogDeletePermissionHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun list(tenant: Tenant, roles: String): String {
        val response = restTemplate.exchange(
            "/tenants/${tenant.id.value}/catalogs",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set(TEST_TENANT_ROLES_HEADER, roles) }),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    @Test
    fun `a publisher without CATALOG_MANAGE is offered no delete`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Delete Publisher Only") } }

        val body = list(tenant, "CONTENT_VIEWER,CONTENT_AUTHOR,CONTENT_PUBLISHER")

        assertThat(body).contains("data-testid=\"catalog-row-export\"")
        assertThat(body).doesNotContain("data-testid=\"catalog-row-delete\"")
    }

    @Test
    fun `an administrator is offered delete`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Delete Administrator") } }

        assertThat(list(tenant, "CONTENT_VIEWER,TENANT_ADMINISTRATOR")).contains("data-testid=\"catalog-row-delete\"")
    }
}
