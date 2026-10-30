// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

@Isolated("Tests in this class require exclusive database access (e.g. asserting empty tenant list)")
class TenantRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET homepage returns tenant list page`() = fixture {
        given {
            tenant("Acme Corp")
            tenant("Globex Inc")
        }

        whenever {
            restTemplate.getForEntity("/", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Tenants")
            assertThat(response.body).contains("Acme Corp")
            assertThat(response.body).contains("Globex Inc")
            assertThat(response.body).`as`("the list leads; creating is a dialog behind New tenant")
                .contains("data-testid=\"tenant-create-open\"").doesNotContain("id=\"create-tenant-form\"")
            assertThat(response.body).`as`("row actions say what they do")
                .containsPattern("</svg>\\s*Namespaces\\s*</a>").containsPattern("</svg>\\s*Delete\\s*</button>")
        }
    }

    @Test
    fun `GET tenants new gives New tenant its dialog, and a link the page with it open`() = fixture {
        whenever {
            val headers = HttpHeaders().apply { set("HX-Request", "true") }
            restTemplate.exchange("/tenants/new", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java) to
                restTemplate.getForEntity("/tenants/new", String::class.java)
        }

        then {
            val (fragment, page) = result<Pair<ResponseEntity<String>, ResponseEntity<String>>>()
            assertThat(fragment.body).contains("id=\"create-tenant-dialog\"").contains("id=\"create-tenant-form\"")
                .doesNotContain("id=\"tenant-list\"")
            assertThat(page.body).contains("id=\"tenant-list\"").contains("id=\"create-tenant-dialog\"")
        }
    }

    @Test
    fun `GET homepage returns empty table when no tenants exist`() = fixture {
        given {
            noTenants()
        }

        whenever {
            restTemplate.getForEntity("/", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Tenants")
            assertThat(response.body).contains("No tenants yet")
        }
    }

    @Test
    fun `GET tenants search filters by name`() = fixture {
        given {
            tenant("Acme Corp")
            tenant("Globex Inc")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            val request = HttpEntity<Void>(headers)
            restTemplate.exchange(
                "/tenants/search?q=Acme",
                org.springframework.http.HttpMethod.GET,
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Acme Corp")
            assertThat(response.body).doesNotContain("Globex Inc")
        }
    }

    @Test
    fun `POST tenants creates new tenant`() = fixture {
        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "new-tenant")
            formData.add("name", "New Tenant")
            formData.add("namespace", "new-tenant")
            formData.add("authority", "gemeente-x.nl")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("New Tenant")
            assertThat(response.headers["HX-Trigger"].orEmpty().joinToString()).`as`("the dialog closes").contains("closeDialog")

            // Clean up tenant created via HTTP (not tracked by fixture)
            val createdTenant = mediator.query(ListTenants("New Tenant")).first()
            mediator.send(DeleteTenant(createdTenant.id))
        }
    }

    @Test
    fun `tenant row links to tenant home page`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
        }

        whenever {
            restTemplate.getForEntity("/", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("/tenants/${tenant.id}")
        }
    }
}
