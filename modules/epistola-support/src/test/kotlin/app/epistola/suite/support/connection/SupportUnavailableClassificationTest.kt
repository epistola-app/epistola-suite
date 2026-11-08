// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.client.RestClientException
import tools.jackson.core.exc.StreamReadException
import java.io.ByteArrayInputStream

/** Which failures mean "try again later" ([isSupportUnavailable]). */
class SupportUnavailableClassificationTest {
    private val input = object : HttpInputMessage {
        override fun getBody() = ByteArrayInputStream(ByteArray(0))

        override fun getHeaders() = HttpHeaders()
    }

    @Test
    fun `an answer that cannot be read is unavailability, not a fault of the item`() {
        val typed = RestClientException("Error while extracting response", HttpMessageNotReadableException("JSON parse error", StreamReadException(null, "Unexpected character '<'"), input))
        val raw = StreamReadException(null, "Unexpected character '<'")

        assertThat(typed.isSupportUnavailable()).isTrue()
        assertThat(raw.isSupportUnavailable()).isTrue()
    }

    @Test
    fun `a plain refusal is not unavailability`() {
        assertThat(IllegalStateException("The support service answered without a feedbackId").isSupportUnavailable()).isFalse()
    }
}
