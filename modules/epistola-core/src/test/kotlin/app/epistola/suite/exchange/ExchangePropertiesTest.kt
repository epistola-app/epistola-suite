// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `application.yaml` ships `base-url:` and `callback-url:` with no value so that discovery and
 * request-derived callbacks are the defaults. Spring binds a valueless YAML key to an empty
 * string, not null, so reading the raw property would treat "unset" as "configured" — and an
 * empty base URL silently disables discovery, which is exactly the shape a production
 * deployment has.
 */
class ExchangePropertiesTest {
    @Test
    fun `a blank optional URL reads as unset`() {
        val properties = ExchangeProperties(baseUrl = "", callbackUrl = "   ")

        assertThat(properties.configuredBaseUrl).isNull()
        assertThat(properties.configuredCallbackUrl).isNull()
    }

    @Test
    fun `a configured URL is preserved and trimmed`() {
        val properties = ExchangeProperties(
            baseUrl = " https://exchange.example ",
            callbackUrl = "https://suite.example/oauth/exchange/callback",
        )

        assertThat(properties.configuredBaseUrl).isEqualTo("https://exchange.example")
        assertThat(properties.configuredCallbackUrl).isEqualTo("https://suite.example/oauth/exchange/callback")
    }

    @Test
    fun `plaintext Exchange is refused by default`() {
        assertThat(ExchangeProperties().allowHttp).isFalse()
    }
}
