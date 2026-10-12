// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * The `/api` chain disables CSRF protection, which is safe only because it never authenticates
 * from the browser session: a cross-site request carries the session cookie, but the chain is
 * stateless and accepts only an API key or bearer token.
 *
 * This boots the production chains — every other app test runs the `test` profile, whose permit-all
 * chain replaces them — signs in through form login, and replays that session cookie against the
 * API. Without it, CodeQL's `java/spring-disabled-csrf-protection` alert has no evidence behind its
 * dismissal.
 */
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
        "epistola.storage.backend=memory",
        "epistola.logs.enabled=false",
        "epistola.catalog.upstream-check.enabled=false",
        "epistola.generation.polling.enabled=false",
    ],
)
@ActiveProfiles(profiles = ["localauth"], inheritProfiles = false)
class ApiChainIgnoresSessionIT : IntegrationTestBase() {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Test
    fun `a signed-in browser session does not authenticate an API request`() {
        val session = signIn()

        // The session is real: the UI accepts it, and turns the same request away without it.
        assertThat(get("/", session).headers().firstValue("Location").orElse(""))
            .doesNotEndWith("/login")
        assertThat(get("/", cookie = null).headers().firstValue("Location").orElse(""))
            .endsWith("/login")

        assertThat(get("/api/tenants", session).statusCode()).isEqualTo(401)
        // The cross-site request CSRF protection would otherwise stop: a state-changing POST
        // carrying only the victim's cookie.
        val post = send(
            HttpRequest.newBuilder(uri("/api/tenants"))
                .header("Cookie", session)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"id":"csrf-probe","name":"CSRF probe"}""")),
        )
        assertThat(post.statusCode()).isEqualTo(401)
    }

    /** Signs in as the `localauth` admin and returns the authenticated session cookie. */
    private fun signIn(): String {
        val loginPage = get("/login", cookie = null)
        assertThat(loginPage.statusCode()).isEqualTo(200)
        val csrf = Regex("""name="_csrf"\s+value="([^"]+)"""").find(loginPage.body())?.groupValues?.get(1)
            ?: Regex("""value="([^"]+)"\s+name="_csrf"""").find(loginPage.body())!!.groupValues[1]
        // csrf.spa() keeps the token in an XSRF-TOKEN cookie; no session exists before sign-in.
        val anonymous = cookies(loginPage)

        val form = mapOf("username" to "admin@local", "password" to "admin", "_csrf" to csrf)
            .entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
        val login = send(
            HttpRequest.newBuilder(uri("/login"))
                .header("Cookie", anonymous)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)),
        )
        assertThat(login.statusCode()).isEqualTo(302)
        assertThat(login.headers().firstValue("Location").orElse("")).doesNotContain("error")
        // Spring Session names its cookie `sid`.
        val session = login.headers().allValues("Set-Cookie").map { it.substringBefore(';') }
            .firstOrNull { it.startsWith("sid=") }
        assertThat(session).describedAs("session cookie issued on sign-in").isNotNull()
        return session!!
    }

    private fun get(path: String, cookie: String?): HttpResponse<String> = send(
        HttpRequest.newBuilder(uri(path)).GET().apply { if (cookie != null) header("Cookie", cookie) },
    )

    private fun send(request: HttpRequest.Builder): HttpResponse<String> = http.send(request.build(), HttpResponse.BodyHandlers.ofString())

    private fun uri(path: String) = URI.create("http://localhost:$port$path")

    private fun cookies(response: HttpResponse<*>): String = response.headers().allValues("Set-Cookie")
        .joinToString("; ") { it.substringBefore(';') }
}
