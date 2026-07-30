// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.util.UUID

class UiNotFoundPageTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `unmatched tenant page renders tenant shell with 404 status`() {
        val tenant = createTenant("Not Found Shell")

        val response = getHtml("/tenants/${tenant.id.value}/does-not-exist")

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body)
            .withFailMessage("404 response headers: %s", response.headers)
            .contains("id=\"app-nav\"", "class=\"app-footer\"", "Page not found")
            .contains("/tenants/${tenant.id.value}")
            .contains("Not Found Shell")
    }

    @Test
    fun `empty handler 404 is upgraded generically to tenant shell`() {
        val tenant = createTenant("Handled Not Found")

        val response = getHtml("/tenants/${tenant.id.value}/load-tests/${UUID.randomUUID()}")

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body)
            .withFailMessage("404 response headers: %s", response.headers)
            .contains("id=\"app-nav\"", "class=\"app-footer\"", "Page not found")
            .contains("Handled Not Found")
    }

    @Test
    fun `unmatched application page renders generic shell`() {
        val response = getHtml("/does-not-exist")

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body)
            .contains("id=\"app-nav\"", "class=\"app-footer\"", "Page not found")
            .contains("View tenants")
    }

    @Test
    fun `boosted navigation receives shell and error swap override`() {
        val tenant = createTenant("Boosted Not Found")
        val headers = htmlHeaders().apply {
            set("HX-Request", "true")
            set("HX-Boosted", "true")
        }

        val response = restTemplate.exchange(
            "/tenants/${tenant.id.value}/load-tests/${UUID.randomUUID()}",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.getFirst("HX-Reswap"))
            .withFailMessage("404 response headers: %s", response.headers)
            .isEqualTo("innerHTML")
        assertThat(response.body)
            .withFailMessage("404 response headers: %s", response.headers)
            .contains("id=\"app-nav\"", "Page not found")
    }

    @Test
    fun `fragment and structured 404 contracts are unchanged`() {
        val problemDocResponse = getHtml("/errors/does-not-exist")
        val fragmentHeaders = htmlHeaders().apply { set("HX-Request", "true") }
        val fragmentResponse = restTemplate.exchange(
            "/errors/does-not-exist",
            HttpMethod.GET,
            HttpEntity<Void>(fragmentHeaders),
            String::class.java,
        )
        val jsonHeaders = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }
        val jsonResponse = restTemplate.exchange(
            "/does-not-exist",
            HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders),
            String::class.java,
        )

        assertThat(problemDocResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(problemDocResponse.body).isNullOrEmpty()
        assertThat(fragmentResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(fragmentResponse.body).isNullOrEmpty()
        assertThat(jsonResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(jsonResponse.body).doesNotContain("Page not found", "id=\"app-nav\"")
    }

    private fun getHtml(path: String) = restTemplate.exchange(
        path,
        HttpMethod.GET,
        HttpEntity<Void>(htmlHeaders()),
        String::class.java,
    )

    private fun htmlHeaders() = HttpHeaders().apply { accept = listOf(MediaType.TEXT_HTML) }
}
