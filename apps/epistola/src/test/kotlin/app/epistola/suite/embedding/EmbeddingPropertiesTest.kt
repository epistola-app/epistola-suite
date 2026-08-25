// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.embedding

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [SecurityConfig][app.epistola.suite.config.SecurityConfig] and
 * [SessionConfig][app.epistola.suite.config.SessionConfig] only ever run under
 * the `!test` profile (ADR 0015's frame-ancestors/X-Frame-Options/cookie
 * SameSite decisions live in `uiSecurityFilterChain`, `@Profile("!test")`), so
 * a Spring-context integration test can't exercise them — the test profile's
 * `testSecurityFilterChain` is permit-all and sets no headers at all. These
 * decisions were pulled out as pure properties on [EmbeddingProperties]
 * specifically so they're directly unit-testable without a Spring context.
 */
class EmbeddingPropertiesTest {

    @Test
    fun `disabled by default keeps today's frame-ancestors 'none'`() {
        val properties = EmbeddingProperties()
        assertThat(properties.cspFrameAncestors).isEqualTo("'none'")
        assertThat(properties.disableXFrameOptions).isFalse()
        assertThat(properties.cookiesNeedSameSiteNone).isFalse()
    }

    @Test
    fun `enabled with no configured origin still falls back to 'none' rather than an empty directive`() {
        val properties = EmbeddingProperties(enabled = true, allowedParentOrigins = emptyList())
        assertThat(properties.cspFrameAncestors).isEqualTo("'none'")
        // X-Frame-Options and cookie SameSite decisions follow `enabled` alone —
        // an operator turning embedding on without an origin is a config
        // mistake, but framing still fails closed via frame-ancestors 'none'.
        assertThat(properties.disableXFrameOptions).isTrue()
        assertThat(properties.cookiesNeedSameSiteNone).isTrue()
    }

    @Test
    fun `enabled with one origin relaxes frame-ancestors, X-Frame-Options, and cookie SameSite together`() {
        val properties = EmbeddingProperties(enabled = true, allowedParentOrigins = listOf("https://epistola.app"))
        assertThat(properties.cspFrameAncestors).isEqualTo("https://epistola.app")
        assertThat(properties.disableXFrameOptions).isTrue()
        assertThat(properties.cookiesNeedSameSiteNone).isTrue()
    }

    @Test
    fun `multiple allowed origins join as space-separated CSP source expressions`() {
        val properties = EmbeddingProperties(
            enabled = true,
            allowedParentOrigins = listOf("https://epistola.app", "https://training.epistola.app"),
        )
        assertThat(properties.cspFrameAncestors).isEqualTo("https://epistola.app https://training.epistola.app")
    }

    @Test
    fun `disabled ignores any configured origins`() {
        val properties = EmbeddingProperties(enabled = false, allowedParentOrigins = listOf("https://epistola.app"))
        assertThat(properties.cspFrameAncestors).isEqualTo("'none'")
        assertThat(properties.disableXFrameOptions).isFalse()
        assertThat(properties.cookiesNeedSameSiteNone).isFalse()
    }
}
