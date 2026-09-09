// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.exchange.ExchangeProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Pins what the shipped `application.yaml` actually binds to. The local profile sets `base-url`,
 * so every test and every developer run took the escape hatch and nothing exercised the default
 * discovery path — the one production uses.
 */
class ExchangeDefaultConfigurationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var properties: ExchangeProperties

    @Test
    fun `the shipped configuration leaves the escape hatch unset so discovery is used`() {
        assertThat(properties.configuredBaseUrl).isNull()
        assertThat(properties.configuredCallbackUrl).isNull()
        assertThat(properties.discoveryUrl).startsWith("https://")
        assertThat(properties.allowHttp).isFalse()
        assertThat(properties.enabled).isFalse()
    }
}
