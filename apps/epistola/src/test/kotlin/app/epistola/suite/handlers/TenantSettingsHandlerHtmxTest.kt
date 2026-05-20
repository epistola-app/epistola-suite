package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.features.queries.GetFeatureToggles
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.SetTenantDefaultLocale
import app.epistola.suite.tenants.queries.GetTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TenantSettingsHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET settings renders the locale form with the effective value`() {
        val tenant = createTenant("Settings Get")

        val response = restTemplate.getForEntity(
            "/tenants/${tenant.id.value}/settings",
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Default locale")
        assertThat(response.body).contains("Effective locale")
        // No override yet → effective falls back to the app default (en-US).
        assertThat(response.body).contains("en-US")
    }

    @Test
    fun `HTMX POST with a valid locale returns 200 fragment with HX-Trigger`() {
        val tenant = createTenant("Settings Update OK")

        val response = postHtmxForm(
            "/tenants/${tenant.id.value}/settings/locale",
            mapOf("locale" to "nl-NL"),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("nl-NL")
        assertThat(response.headers.getFirst("HX-Trigger")).isEqualTo("tenantSettingsUpdated")
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isEqualTo("nl-NL")
    }

    @Test
    fun `HTMX POST with an unknown locale re-renders the form with an inline error`() {
        val tenant = createTenant("Settings Update Bogus")

        val response = postHtmxForm(
            "/tenants/${tenant.id.value}/settings/locale",
            mapOf("locale" to "xx-ZZ"),
        )

        // Re-render the form, never a 3xx. Inline error tells the user what
        // happened; the tenant row is untouched.
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Unknown locale")
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isNull()
    }

    @Test
    fun `HTMX POST with an empty locale clears the override`() {
        val tenant = createTenant("Settings Clear")
        withMediator {
            SetTenantDefaultLocale(tenantId = tenant.id, locale = "nl-NL").execute()
        }

        val response = postHtmxForm(
            "/tenants/${tenant.id.value}/settings/locale",
            mapOf("locale" to ""),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val reloaded: Tenant? = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isNull()
    }

    @Test
    fun `GET settings renders the feature toggles form`() {
        val tenant = createTenant("Settings Features Get")

        val response = restTemplate.getForEntity(
            "/tenants/${tenant.id.value}/settings",
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Feature toggles")
        assertThat(response.body).contains(KnownFeatures.FEEDBACK.value)
    }

    @Test
    fun `HTMX POST features enables the toggles whose checkboxes are present`() {
        val tenant = createTenant("Settings Features Toggle")

        // Checkbox present → enabled; absent → disabled. We send `feedback`
        // only, so STENCIL_PARAMETERS must end up false.
        val response = postHtmxForm(
            "/tenants/${tenant.id.value}/settings/features",
            mapOf(KnownFeatures.FEEDBACK.value to "on"),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst("HX-Trigger")).isEqualTo("tenantSettingsUpdated")
        val toggles = withMediator { GetFeatureToggles(tenant.id).query() }
        assertThat(toggles[KnownFeatures.FEEDBACK]).isTrue()
        assertThat(toggles[KnownFeatures.STENCIL_PARAMETERS]).isFalse()
    }

    @Test
    fun `HTMX POST features disables a previously-enabled feature whose checkbox is now omitted`() {
        // Regression-shaped: the dangerous path for checkbox forms — an unchecked
        // box sends no param, so the handler MUST interpret "absent" as false,
        // not "leave the previous value alone".
        val tenant = createTenant("Settings Features Uncheck")
        withMediator {
            SaveFeatureToggle(
                tenantKey = tenant.id,
                featureKey = KnownFeatures.FEEDBACK,
                enabled = true,
            ).execute()
        }

        val response = postHtmxForm(
            "/tenants/${tenant.id.value}/settings/features",
            // Empty form body: both checkboxes were unchecked at submit time.
            emptyMap(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val toggles = withMediator { GetFeatureToggles(tenant.id).query() }
        assertThat(toggles[KnownFeatures.FEEDBACK]).isFalse()
    }

    @Test
    fun `non-HTMX POST locale redirects the browser to the settings page`() {
        // Progressive-enhancement path: a plain form submit (no HX-Request
        // header) should 303 back to the settings page so the browser ends up
        // on a usable URL.
        val tenant = createTenant("Settings Plain Redirect")

        val response = postPlainForm(
            "/tenants/${tenant.id.value}/settings/locale",
            mapOf("locale" to "nl-NL"),
        )

        // TestRestTemplate follows redirects exactly as a browser would, so the
        // chain ends on the rendered settings page (200) — NOT a JSON blob.
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Default locale")
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isEqualTo("nl-NL")
    }

    private fun postHtmxForm(
        url: String,
        form: Map<String, String>,
    ): ResponseEntity<String> = post(url, form, htmx = true)

    /** A plain `<form>` POST — no `HX-Request` header. */
    private fun postPlainForm(
        url: String,
        form: Map<String, String>,
    ): ResponseEntity<String> = post(url, form, htmx = false)

    private fun post(
        url: String,
        form: Map<String, String>,
        htmx: Boolean,
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            if (htmx) set("HX-Request", "true")
            contentType = MediaType.APPLICATION_FORM_URLENCODED
        }
        val body = LinkedMultiValueMap<String, String>().apply {
            form.forEach { (k, v) -> add(k, v) }
        }
        return restTemplate.postForEntity(url, HttpEntity(body, headers), String::class.java)
    }
}
