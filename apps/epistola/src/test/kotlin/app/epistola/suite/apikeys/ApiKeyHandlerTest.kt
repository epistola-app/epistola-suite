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
    fun `GET new returns the create dialog`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("API Key New Form")
        }

        whenever {
            // The create form is dialog-only; HTMX loads the dialog fragment.
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
            assertThat(response.body).contains("create-api-key-dialog")
            assertThat(response.body).contains("name=\"name\"")
            assertThat(response.body).contains("name=\"expiresAt\"")
        }
    }

    @Test
    fun `GET new over HTMX pushes the create URL`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("API Key Push URL")
        }

        whenever {
            val headers = HttpHeaders().apply {
                set("HX-Request", "true")
                set("HX-Current-URL", "/tenants/${testTenant.id}/api-keys")
            }
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
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${testTenant.id}/api-keys?create")
        }
    }

    @Test
    fun `GET list with create renders the dialog markup for deep linking`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("API Key Deeplink")
        }

        whenever {
            // Direct (non-HTMX) navigation to the deep link must ship the closed
            // dialog markup (with the data-create-dialog hook) for the reconcile script.
            restTemplate.getForEntity("/tenants/${testTenant.id}/api-keys?create", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("create-api-key-dialog")
            assertThat(response.body).contains("data-create-dialog")
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
            // Success renders the full api-keys/created page (plain boosted form) with the
            // one-time plaintext key.
            assertThat(response.body).contains("Copy this key now")
            assertThat(response.body).contains("api-key-secret")
            val match = Regex("""epk_[A-Za-z0-9_-]+""").find(response.body!!)
            assertThat(match).isNotNull
            assertThat(match!!.value.length).isGreaterThan(20)

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
            assertThat(response.body).contains("Name is required")
            // No key was persisted
            val keys = withMediator { ListApiKeys(tenantId = testTenant.id).query() }
            assertThat(keys).isEmpty()
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
