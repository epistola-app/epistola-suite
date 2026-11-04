// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.namespaces.ListNamespaceGrants
import app.epistola.suite.catalog.namespaces.LocalAuthority
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.testing.TEST_TENANT_MEMBERSHIPS_HEADER
import app.epistola.suite.testing.testCatalog
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
                .containsPattern("</svg>\\s*Delete\\s*</button>")
        }
    }

    @Test
    fun `GET homepage takes someone in one tenant, who manages none, straight to it`() = fixture {
        lateinit var one: Tenant
        lateinit var two: Tenant
        given {
            one = tenant("Only Tenant")
            two = tenant("Second Tenant")
        }
        whenever {
            fun asMemberOf(vararg tenants: Tenant) = restTemplate.exchange(
                "/",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { set(TEST_TENANT_MEMBERSHIPS_HEADER, tenants.joinToString(",") { it.id.value }) }),
                String::class.java,
            ).body!!
            listOf(asMemberOf(one), asMemberOf(one, two))
        }
        then {
            val (member, memberOfTwo) = result<List<String>>()
            // A list with one row to click through was all a one-tenant user had on signing in.
            assertThat(member).contains("<title>Only Tenant - Epistola</title>").doesNotContain("<title>Tenants - Epistola</title>")
            assertThat(memberOfTwo).`as`("two tenants: still a choice").contains("<title>Tenants - Epistola</title>")
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

    /** Posts the New tenant form as the dialog does. */
    private fun postTenant(vararg fields: Pair<String, String>): ResponseEntity<String> = restTemplate.postForEntity(
        "/tenants",
        HttpEntity(
            LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } },
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                set("HX-Request", "true")
            },
        ),
        String::class.java,
    )

    @Test
    fun `a tenant is created with a name and an ID only, and its catalogs start under local`() {
        // Its administrator connects authorities when it needs a name of its own (ADR 0034, amendment
        // of 2026-11-03); a namespace posted anyway is not granted.
        val key = "no-ns-${System.nanoTime().toString().takeLast(8)}"
        val response = postTenant("slug" to key, "name" to "No Namespace $key", "namespace" to key, "authority" to "gemeente-x.nl")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers["HX-Trigger"].orEmpty().joinToString()).`as`("the dialog closes").contains("closeDialog")
        val tenant = withMediator { ListTenants(idPrefix = key).query().single() }
        assertThat(withMediator { ListNamespaceGrants(tenant.id).query() }).`as`("no namespace granted").isEmpty()
        val default = withMediator { GetCatalog(testCatalog(tenant.id, CatalogKey.DEFAULT)).query() }!!
        assertThat(default.registry to default.namespace).isEqualTo(LocalAuthority.NAME to LocalAuthority.DEFAULT_NAMESPACE)
        withMediator { DeleteTenant(tenant.id).execute() }
    }

    @Test
    fun `New tenant asks for a name and an ID, and nothing about naming catalogs`() {
        val body = restTemplate.exchange(
            "/tenants/new",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
            String::class.java,
        ).body!!

        assertThat(body).contains("name=\"name\"").contains("name=\"slug\"")
        assertThat(body).doesNotContain("name=\"namespace\"").doesNotContain("name=\"authority\"").doesNotContain("tenant-naming")
    }

    @Test
    fun `the tenant list no longer leads to a tenant's namespaces, which are the tenant's own to manage`() = fixture {
        given { tenant("Listed Tenant") }
        whenever { restTemplate.getForEntity("/", String::class.java) }
        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("Listed Tenant").doesNotContain("tenant-namespaces-link").doesNotContain("/namespaces")
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
