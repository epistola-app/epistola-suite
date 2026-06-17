package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.SetTenantDefaultLocale
import app.epistola.suite.tenants.queries.GetTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

class DefaultsHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET defaults renders the locale form with the effective value`() {
        val tenant = createTenant("Defaults Get")

        val response = restTemplate.getForEntity(
            "/tenants/${tenant.id.value}/defaults",
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Default locale")
        assertThat(response.body).contains("Effective locale")
        // No override yet → effective falls back to the app default (en-US).
        assertThat(response.body).contains("en-US")
    }

    @Test
    fun `POST a valid locale persists the override and the saved page is shown`() {
        val tenant = createTenant("Defaults Update OK")

        // TestRestTemplate follows the 303 by default → we land on the GET page
        // with ?saved=true so the success banner is rendered.
        val response = postForm(
            "/tenants/${tenant.id.value}/defaults/locale",
            mapOf("locale" to "nl-NL"),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Defaults saved successfully")
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isEqualTo("nl-NL")
    }

    @Test
    fun `POST an unknown locale re-renders the page with an inline error and leaves the row untouched`() {
        val tenant = createTenant("Defaults Update Bogus")

        // Failure path does NOT redirect — the handler re-renders the page
        // directly with the `error` model attribute set, so the user keeps the
        // tab they were on. The row stays untouched.
        val response = postForm(
            "/tenants/${tenant.id.value}/defaults/locale",
            mapOf("locale" to "xx-ZZ"),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Unknown locale")
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isNull()
    }

    @Test
    fun `POST an empty locale clears a previously-set override`() {
        val tenant = createTenant("Defaults Clear")
        withMediator {
            SetTenantDefaultLocale(tenantId = tenant.id, locale = "nl-NL").execute()
        }

        val response = postForm(
            "/tenants/${tenant.id.value}/defaults/locale",
            mapOf("locale" to ""),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isNull()
    }

    private fun postForm(url: String, form: Map<String, String>): ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        val body = LinkedMultiValueMap<String, String>().apply {
            form.forEach { (k, v) -> add(k, v) }
        }
        return restTemplate.postForEntity(url, HttpEntity(body, headers), String::class.java)
    }
}
