// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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

/**
 * The Defaults page: page render, and the locale save flow — an HTMX submit
 * re-renders the locale form with an OOB success notice, a plain submit falls
 * back to a PRG redirect, and an unknown locale reports into the form's global
 * error slot without persisting.
 */
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
    fun `htmx save re-renders the locale form with an OOB success notice`() {
        val tenant = createTenant("Defaults Update Htmx")

        val response = postForm(
            "/tenants/${tenant.id.value}/defaults/locale",
            mapOf("locale" to "nl-NL"),
            htmxForm(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        // Primary swap: the form fragment (with the new effective locale), not a full page.
        assertThat(body).contains("Effective locale")
        assertThat(body).contains("nl-NL")
        assertThat(body).doesNotContain("<title")
        // The success notice rides the same response as an OOB swap into #notices.
        assertThat(body).contains("hx-swap-oob=\"afterbegin:#notices\"")
        assertThat(body).contains("Defaults saved.")
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isEqualTo("nl-NL")
    }

    @Test
    fun `plain save persists the override and falls back to a PRG redirect`() {
        val tenant = createTenant("Defaults Update OK")

        // The test client follows the 303 (POST becomes GET), so the response is
        // the defaults page again — no notice on the plain path.
        val response = postForm(
            "/tenants/${tenant.id.value}/defaults/locale",
            mapOf("locale" to "nl-NL"),
            plainForm(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Default locale")
        assertThat(response.body).doesNotContain("Defaults saved.")
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isEqualTo("nl-NL")
    }

    @Test
    fun `htmx save of an unknown locale fills the form error slot and leaves the row untouched`() {
        val tenant = createTenant("Defaults Update Bogus Htmx")

        val response = postForm(
            "/tenants/${tenant.id.value}/defaults/locale",
            mapOf("locale" to "xx-ZZ"),
            htmxForm(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
        val body = response.body!!
        assertThat(body).contains("id=\"defaults-locale-error\"")
        assertThat(body).contains("Unknown locale")
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isNull()
    }

    @Test
    fun `plain save of an unknown locale re-renders the page with an inline error`() {
        val tenant = createTenant("Defaults Update Bogus")

        val response = postForm(
            "/tenants/${tenant.id.value}/defaults/locale",
            mapOf("locale" to "xx-ZZ"),
            plainForm(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
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
            plainForm(),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isNull()
    }

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    /** A plain browser form POST — no HX-Request, i.e. the no-JS fallback path. */
    private fun plainForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
    }

    private fun postForm(url: String, form: Map<String, String>, headers: HttpHeaders): ResponseEntity<String> {
        val body = LinkedMultiValueMap<String, String>().apply {
            form.forEach { (k, v) -> add(k, v) }
        }
        return restTemplate.postForEntity(url, HttpEntity(body, headers), String::class.java)
    }
}
