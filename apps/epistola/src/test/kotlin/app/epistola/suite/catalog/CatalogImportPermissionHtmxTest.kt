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
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * Importing an archive receives a release, which [app.epistola.suite.catalog.commands.ImportCatalogZip]
 * allows with `CATALOG_MANAGE`; the import page checks the same permission, and offers the control
 * to whoever holds it, and to nobody else.
 */
class CatalogImportPermissionHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun headers(roles: String) = HttpHeaders().apply {
        set(TEST_TENANT_ROLES_HEADER, roles)
        set("HX-Request", "true")
    }

    private fun get(tenant: Tenant, path: String, roles: String) = restTemplate.exchange("/tenants/${tenant.id.value}$path", HttpMethod.GET, HttpEntity<Void>(headers(roles)), String::class.java)

    @Test
    fun `an author without CATALOG_MANAGE gets no import control and is refused`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Import Author Only") } }
        val author = "CONTENT_VIEWER,CONTENT_AUTHOR"

        val list = restTemplate.exchange("/tenants/${tenant.id.value}/catalogs", HttpMethod.GET, HttpEntity<Void>(HttpHeaders().apply { set(TEST_TENANT_ROLES_HEADER, author) }), String::class.java)
        assertThat(list.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(list.body).doesNotContain("data-testid=\"catalog-import-open\"")

        assertThat(get(tenant, "/catalogs/import", author).statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val upload = LinkedMultiValueMap<String, Any>().apply {
            add(
                "file",
                object : ByteArrayResource(ByteArray(0)) {
                    override fun getFilename() = "catalog.zip"
                },
            )
        }
        val posted = restTemplate.exchange(
            "/tenants/${tenant.id.value}/catalogs/import",
            HttpMethod.POST,
            HttpEntity(upload, headers(author).apply { contentType = MediaType.MULTIPART_FORM_DATA }),
            String::class.java,
        )
        assertThat(posted.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `an administrator with CATALOG_MANAGE but no edit permission gets the import control and its dialog`() {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Import Admin Only") } }
        val admin = "CONTENT_VIEWER,TENANT_ADMINISTRATOR"

        val list = restTemplate.exchange("/tenants/${tenant.id.value}/catalogs", HttpMethod.GET, HttpEntity<Void>(HttpHeaders().apply { set(TEST_TENANT_ROLES_HEADER, admin) }), String::class.java)
        assertThat(list.body).contains("data-testid=\"catalog-import-open\"")

        val dialog = get(tenant, "/catalogs/import", admin)
        assertThat(dialog.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(dialog.body).contains("/catalogs/import")
    }
}
