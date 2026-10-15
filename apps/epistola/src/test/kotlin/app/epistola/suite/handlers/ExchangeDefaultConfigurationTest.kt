// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.exchange.ExchangeProperties
import app.epistola.suite.productdomain.ProductDomainProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Pins what the shipped `application.yaml` actually binds to. The local profile sets `endpoint`,
 * so every test and every developer run took the escape hatch and nothing exercised the default
 * discovery path — the one production uses.
 */
class ExchangeDefaultConfigurationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var properties: ExchangeProperties

    @Autowired
    private lateinit var productDomain: ProductDomainProperties

    @Test
    fun `the shipped configuration leaves the escape hatch unset so discovery is used`() {
        assertThat(properties.configuredEndpoint).isNull()
        assertThat(properties.configuredCallbackUrl).isNull()
        // The test profile points the product domain at `epistola.invalid`; what ships is epistola.app.
        assertThat(productDomain.productDomain).isEqualTo("epistola.invalid")
        assertThat(ProductDomainProperties().productDomain).isEqualTo("epistola.app")
        assertThat(properties.allowHttp).isFalse()
        assertThat(properties.enabled).isFalse()
    }
}
