// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.feedback

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.FeedbackKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/** Submitting feedback that does not validate shows the form again in its dialog, with the errors by their fields. */
class FeedbackSubmitHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a title of only spaces returns the form with its error, shaped for the dialog`() {
        val tenant = createTenant("Feedback Submit")
        val form = LinkedMultiValueMap<String, String>().apply {
            add("title", "   ") // passes the browser's `required`, trims to blank on the server
            add("description", "Something is off")
            add("category", "BUG")
            add("priority", "MEDIUM")
        }

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/feedback",
            HttpEntity(
                form,
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_FORM_URLENCODED
                    set("HX-Request", "true")
                },
            ),
            String::class.java,
        )

        assertThat(response.statusCode.value()).isEqualTo(422)
        assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("innerHTML")
        assertThat(response.body).`as`("the form, not a whole page").doesNotContain("<html").contains("data-feedback-form")
        assertThat(response.body).contains("class=\"form-error\"").contains("Something is off")
    }

    @Test
    fun `a blank comment is refused in its form`() {
        val tenant = createTenant("Feedback Comment")

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/feedback/${FeedbackKey.generate().value}/comments",
            HttpEntity(
                LinkedMultiValueMap<String, String>().apply { add("body", "   ") },
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_FORM_URLENCODED
                    set("HX-Request", "true")
                },
            ),
            String::class.java,
        )

        assertThat(response.statusCode.value()).isEqualTo(422)
        assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
        assertThat(response.body).contains("id=\"add-comment-error\"").contains("Write a comment first.")
    }
}
