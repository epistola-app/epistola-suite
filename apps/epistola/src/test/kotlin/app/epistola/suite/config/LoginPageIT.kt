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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The sign-in form: labelled fields the browser can fill, and one action named as people say it.
 * The form only renders with form login (`localauth`), which the `test` profile replaces; this uses
 * the same context as [ApiChainIgnoresSessionIT], so Spring's context cache shares it.
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
class LoginPageIT : IntegrationTestBase() {

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `the form's fields carry their labels and autocomplete hints, not a placeholder repeating the label`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertThat(response.statusCode()).isEqualTo(200)
        val body = response.body()
        assertThat(body).contains("<label for=\"username\">Email</label>").contains("autocomplete=\"username\"")
        assertThat(body).contains("<label for=\"password\">Password</label>").contains("autocomplete=\"current-password\"")
        assertThat(body).doesNotContain("placeholder=\"Email\"").doesNotContain("placeholder=\"Password\"")
        assertThat(body).contains(">Sign in</button>")
    }
}
