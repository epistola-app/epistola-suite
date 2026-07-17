package app.epistola.suite.apikeys

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.apikeys.queries.ListApiKeys
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET list returns empty state when tenant has no keys`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("API Key List Empty")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/api-keys",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("API Keys")
            assertThat(response.body).contains("No API keys")
        }
    }

    @Test
    fun `non-HTMX GET new renders the list page with the dialog embedded and open`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("API Key New Form")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/api-keys/new",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Full host page (app shell) with the dialog embedded in the mount.
            assertThat(response.body).contains("<html")
            assertThat(response.body).contains("id=\"dialog-mount\"")
            assertThat(response.body).contains("id=\"create-api-key-dialog\"")
            assertThat(response.body).contains("name=\"name\"")
            assertThat(response.body).contains("name=\"expiresAt\"")
        }
    }

    @Test
    fun `HTMX GET new returns the dialog fragment with the create form`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("API Key New Dialog")
        }

        whenever {
            val headers = HttpHeaders().apply { set("HX-Request", "true") }
            restTemplate.exchange(
                "/tenants/${testTenant.id}/api-keys/new",
                org.springframework.http.HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("id=\"create-api-key-dialog\"")
            assertThat(response.body).contains("id=\"create-api-key-form\"")
            assertThat(response.body).contains("name=\"name\"")
            assertThat(response.body).contains("data-testid=\"api-key-roles\"")
            // A fragment, not the whole page (no app shell).
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `POST create returns plaintext key and persists an enabled record`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("API Key Create")
        }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("name", "CI integration")
            form.add("roles", "CONTENT_VIEWER")
            form.add("roles", "DOCUMENT_GENERATOR")
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                set("HX-Request", "true")
            }
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/api-keys",
                HttpEntity(form, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Success is a REVEAL: the one-time key panel is swapped into the dialog
            // in place of the form (retarget #create-api-key-form + outerHTML) and
            // the dialog STAYS OPEN (no closeDialog trigger).
            assertThat(response.body).contains("Copy this key now")
            assertThat(response.body).contains("api-key-secret")
            val match = Regex("""epk_[A-Za-z0-9_-]+""").find(response.body!!)
            assertThat(match).isNotNull
            assertThat(match!!.value.length).isGreaterThan(20)
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-api-key-form")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()

            // Persisted as enabled
            val keys = withMediator { ListApiKeys(tenantId = testTenant.id).query() }
            assertThat(keys).hasSize(1)
            assertThat(keys[0].name).isEqualTo("CI integration")
            assertThat(keys[0].enabled).isTrue()
        }
    }

    @Test
    fun `POST create rejects blank name with validation error`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("API Key Validation")
        }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("name", "")
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
            }
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/api-keys",
                HttpEntity(form, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // Non-HTMX invalid submit → host list page re-rendered at 422 with the
            // dialog embedded and inline errors.
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(response.body).contains("Name is required")
            // No key was persisted
            val keys = withMediator { ListApiKeys(tenantId = testTenant.id).query() }
            assertThat(keys).isEmpty()
        }
    }

    @Test
    fun `HTMX POST invalid retargets the form with 422 and inline errors`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("API Key HTMX Invalid")
        }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("name", "")
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                set("HX-Request", "true")
            }
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/api-keys",
                HttpEntity(form, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // Retargets the FORM (not the dialog, not the list) so the open modal
            // dialog is never removed from the top layer, and stays open.
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-api-key-form")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            assertThat(response.body).contains("id=\"create-api-key-form\"")
            assertThat(response.body).contains("Name is required")
        }
    }

    @Test
    fun `plain list route does not embed the create dialog`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("API Key Plain List")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/api-keys",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The mount is present but empty: no openDialog flag on the plain list
            // route, so the create dialog must NOT be embedded.
            assertThat(response.body).contains("id=\"dialog-mount\"")
            assertThat(response.body).doesNotContain("id=\"create-api-key-dialog\"")
        }
    }

    @Test
    fun `POST delete revokes the key and list filters it out`() = fixture {
        lateinit var testTenant: Tenant
        lateinit var revokedKeyId: String

        given {
            testTenant = tenant("API Key List Filter")
            val toRevoke = CreateApiKey(tenantId = testTenant.id, name = "Soon revoked").execute()
            CreateApiKey(tenantId = testTenant.id, name = "Live key").execute()
            revokedKeyId = toRevoke.apiKey.id.value.toString()
        }

        whenever {
            val headers = HttpHeaders().apply { set("HX-Request", "true") }
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/api-keys/$revokedKeyId/delete",
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val deleteResponse = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(deleteResponse.statusCode).isEqualTo(HttpStatus.OK)
            // HTMX response is the rows fragment showing only the live key
            assertThat(deleteResponse.body).contains("Live key")
            assertThat(deleteResponse.body).doesNotContain("Soon revoked")

            // Subsequent full list page also filters it out
            val listResponse = restTemplate.getForEntity(
                "/tenants/${testTenant.id}/api-keys",
                String::class.java,
            )
            assertThat(listResponse.body).contains("Live key")
            assertThat(listResponse.body).doesNotContain("Soon revoked")

            // Underlying record still exists in DB but disabled
            val all = withMediator { ListApiKeys(tenantId = testTenant.id).query() }
            assertThat(all).hasSize(2)
            assertThat(all.first { it.name == "Soon revoked" }.enabled).isFalse()
            assertThat(all.first { it.name == "Live key" }.enabled).isTrue()
        }
    }
}
