// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.namespaces.ListNamespaceGrants
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.testing.TEST_TENANT_MEMBERSHIPS_HEADER
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
    fun `a tenant is created without a namespace, and its catalogs stay local`() {
        // A namespace and its authority matter only once catalogs leave this installation; the
        // dialog asked for both before a tenant could exist.
        val key = "no-ns-${System.nanoTime().toString().takeLast(8)}"
        val response = postTenant("slug" to key, "name" to "No Namespace $key", "namespace" to "", "authority" to "")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers["HX-Trigger"].orEmpty().joinToString()).`as`("the dialog closes").contains("closeDialog")
        val tenant = withMediator { ListTenants(idPrefix = key).query().single() }
        assertThat(withMediator { ListNamespaceGrants(tenant.id).query() }).`as`("no namespace granted").isEmpty()
        withMediator { DeleteTenant(tenant.id).execute() }
    }

    @Test
    fun `a namespace without the domain granting it is refused at that field`() {
        val key = "ns-no-auth-${System.nanoTime().toString().takeLast(8)}"
        val response = postTenant("slug" to key, "name" to "Namespace $key", "namespace" to key, "authority" to "")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(response.body!!.substringAfter("id=\"authority\"").substringBefore("</div>"))
            .contains("form-error").contains("domain")
        assertThat(withMediator { ListTenants(idPrefix = key).query() }).isEmpty()
    }

    @Test
    fun `New tenant asks for a name and an ID, with naming its catalogs an optional section and no authority filled in`() {
        val body = restTemplate.exchange(
            "/tenants/new",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
            String::class.java,
        ).body!!

        val naming = body.substringAfter("data-testid=\"tenant-naming\"").substringBefore("</details>")
        assertThat(body).`as`("a closed section").contains("<details").doesNotContain("data-testid=\"tenant-naming\" open")
        assertThat(naming).contains("Catalog naming (optional)").contains("<code>local</code>")
        // Exchange namespaces come through the tenant's Exchange connection, not from this form.
        assertThat(naming.substringAfter("id=\"authority\"").substringBefore(">")).doesNotContain("exchange.epistola.app")
        assertThat(naming.substringAfter("id=\"namespace\"").substringBefore(">")).doesNotContain("required")
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
