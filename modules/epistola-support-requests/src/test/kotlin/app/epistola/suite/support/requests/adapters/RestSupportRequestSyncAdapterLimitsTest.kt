// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.adapters

import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStatus
import app.epistola.suite.support.connection.SupportScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/** The adapter keeps every field within the protocol's size limits. */
class RestSupportRequestSyncAdapterLimitsTest {
    private val mapper = JsonMapper.builder().build()

    private val adapter = RestSupportRequestSyncAdapter(
        object : SupportConnection {
            override fun status() = SupportConnectionStatus(SupportConnectionState.Disabled, null, null, null)
            override fun hasScope(scope: SupportScope) = false
            override fun isSelected(scope: SupportScope) = false
            override fun scopeRefused(scope: SupportScope) = Unit
            override fun apiEndpoint(): URI? = null
            override fun otlpEndpoint(): URI? = null
            override fun cachedAccessToken(): String? = null
            override fun accessToken(): String = error("not used")
            override fun restClient(): RestClient = error("not used")
            override fun checkNow() = Unit
        },
        mapper,
    )

    @Test
    fun `console logs within the limit are sent as they are`() {
        val logs = """[{"level":"log","message":"hi"}]"""

        assertThat(adapter.fitConsoleLogs(logs)).isEqualTo(logs)
        assertThat(adapter.fitConsoleLogs(null)).isNull()
    }

    @Test
    fun `console logs over the limit lose their oldest entries and stay a JSON array`() {
        val entry = "x".repeat(10_000)
        val entries = (0 until 40).map { """{"level":"log","message":"$it-$entry"}""" }
        val logs = entries.joinToString(",", "[", "]")
        assertThat(logs.length).isGreaterThan(RestSupportRequestSyncAdapter.MAX_CONSOLE_LOG_BYTES)

        val fitted = adapter.fitConsoleLogs(logs)!!

        assertThat(fitted.toByteArray().size).isLessThanOrEqualTo(RestSupportRequestSyncAdapter.MAX_CONSOLE_LOG_BYTES)
        val kept = mapper.readTree(fitted)
        assertThat(kept.isArray).isTrue()
        assertThat(kept.size()).isBetween(20, 39)
        assertThat(kept.last().path("message").stringValue()).`as`("the newest entry is kept").startsWith("39-")
    }

    @Test
    fun `console logs over the limit that are no JSON array are left out`() {
        assertThat(adapter.fitConsoleLogs("y".repeat(RestSupportRequestSyncAdapter.MAX_CONSOLE_LOG_BYTES + 1))).isNull()
    }

    @Test
    fun `metadata over the limit is left out`() {
        assertThat(adapter.fitMetadata("""{"a":1}""")).isEqualTo("""{"a":1}""")
        assertThat(adapter.fitMetadata("z".repeat(RestSupportRequestSyncAdapter.MAX_METADATA_BYTES + 1))).isNull()
    }

    @Test
    fun `text within its limit is sent as it is`() {
        assertThat(adapter.fitChars("short", 10)).isEqualTo("short")
        assertThat(adapter.fitText("short")).isEqualTo("short")
    }

    @Test
    fun `a value over its character limit is cut with an ellipsis, never through a surrogate pair`() {
        assertThat(adapter.fitChars("abcdef", 4)).isEqualTo("abc…")
        // "😀" is two UTF-16 characters; cutting after the first would leave half of it.
        val cut = adapter.fitChars("ab😀cd", 4)
        assertThat(cut).isEqualTo("ab…")
        assertThat(cut.length).isLessThanOrEqualTo(4)
    }

    @Test
    fun `text over the byte limit keeps its start, whole characters only, and says it was cut`() {
        val text = "€".repeat(RestSupportRequestSyncAdapter.MAX_TEXT_BYTES / 3 + 10)

        val fitted = adapter.fitText(text)

        assertThat(fitted.toByteArray().size).isLessThanOrEqualTo(RestSupportRequestSyncAdapter.MAX_TEXT_BYTES)
        assertThat(fitted).endsWith(RestSupportRequestSyncAdapter.CUT_MARKER)
        assertThat(fitted.removeSuffix(RestSupportRequestSyncAdapter.CUT_MARKER)).matches("€+")
    }
}
