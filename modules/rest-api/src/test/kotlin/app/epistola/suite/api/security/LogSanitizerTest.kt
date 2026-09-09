// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class LogSanitizerTest {

    @Test
    fun `leaves an ordinary request path alone`() {
        assertThat(sanitizeForLog("/api/tenants/acme/templates?limit=25"))
            .isEqualTo("/api/tenants/acme/templates?limit=25")
    }

    @Test
    fun `neutralises the newlines a caller would forge log lines with`() {
        val forged = "/api/ping\nINFO  Demo shared secret accepted for GET /api/tenants/victim"

        val sanitized = sanitizeForLog(forged)

        assertThat(sanitized).doesNotContain("\n")
        assertThat(sanitized).startsWith("/api/ping_INFO")
    }

    @Test
    fun `neutralises carriage returns and tabs`() {
        assertThat(sanitizeForLog("/a\r\n\tb")).isEqualTo("/a___b")
    }

    @Test
    fun `neutralises terminal escapes that would rewrite what a reader sees`() {
        // ESC[2K clears the line in most terminals — a log a human cats can lie without a newline
        // in sight, so control characters go as a class rather than CR/LF alone.
        assertThat(sanitizeForLog("/a\u001B[2Kb")).isEqualTo("/a_[2Kb")
    }

    @Test
    fun `keeps the value recognisable, which is why it is logged at all`() {
        assertThat(sanitizeForLog("/api/tenants/acme\u0000/x")).isEqualTo("/api/tenants/acme_/x")
    }
}
