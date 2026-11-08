// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.productdomain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

class ProductDomainSettingsTest {
    @Test
    fun `the product domain is a host, optionally with a port`() {
        assertThat(ProductDomainProperties().discoveryUrl).isEqualTo("https://epistola.app/.well-known/epistola.json")
        assertThat(ProductDomainProperties("staging.epistola.app:8443").host).isEqualTo("staging.epistola.app")

        for (value in listOf("", "https://epistola.app", "http://localhost:4321", "epistola.app/", "epistola.app/x", "-epistola.app")) {
            assertThatThrownBy { ProductDomainProperties(value) }.describedAs(value).hasMessageContaining("epistola.product-domain")
        }
    }

    @Test
    fun `a replaced setting fails startup, naming its replacement`() {
        val environment = environment(mapOf("epistola.exchange.base-url" to "https://exchange.internal"))

        assertThatThrownBy { RetiredEndpointSettings(environment).check() }
            .hasMessageContaining("epistola.exchange.base-url -> epistola.exchange.endpoint")
    }

    @Test
    fun `a replaced setting set through an environment variable is found too`() {
        // Spring maps variable names only for the source the real environment uses, by its name.
        val environment = StandardEnvironment().apply {
            propertySources.replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    mapOf<String, Any>("EPISTOLA_VERSIONCHECK_WELLKNOWNURL" to "https://mirror.example/r.json"),
                ),
            )
        }

        assertThatThrownBy { RetiredEndpointSettings(environment).check() }
            .hasMessageContaining("epistola.version-check.well-known-url -> epistola.version-check.endpoint")
    }

    @Test
    fun `the gRPC hub settings fail startup, naming the support service settings`() {
        val environment = environment(mapOf("epistola.support.hub.endpoint" to "hub.internal:443", "epistola.support.hub.plaintext" to "false"))

        assertThatThrownBy { RetiredEndpointSettings(environment).check() }
            .hasMessageContaining("epistola.support.hub.endpoint -> epistola.support.endpoint")
            .hasMessageContaining("epistola.support.hub.plaintext -> epistola.support.allow-http")
    }

    @Test
    fun `a retired group fails startup for any setting under it`() {
        assertThatThrownBy { RetiredEndpointSettings(environment(mapOf("epistola.support.health-check.stale-after" to "5m"))).check() }
            .hasMessageContaining("epistola.support.health-check.* -> epistola.support.check-interval")
        assertThatThrownBy { RetiredEndpointSettings(environment(mapOf("epistola.support.entitlements.refresh-ms" to "1000"))).check() }
            .hasMessageContaining("epistola.support.entitlements.*")
    }

    @Test
    fun `a retired group set through an environment variable is found too`() {
        val environment = StandardEnvironment().apply {
            propertySources.replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    mapOf<String, Any>("EPISTOLA_SUPPORT_ENTITLEMENTS_REFRESHMS" to "1000"),
                ),
            )
        }

        assertThatThrownBy { RetiredEndpointSettings(environment).check() }.hasMessageContaining("epistola.support.entitlements.*")
    }

    @Test
    fun `the support service settings themselves are not retired`() {
        RetiredEndpointSettings(
            environment(mapOf("epistola.support.enabled" to "true", "epistola.support.endpoint" to "https://support.example", "epistola.support.check-interval" to "5m")),
        ).check()
    }

    @Test
    fun `a blank replaced setting is no override and passes`() {
        RetiredEndpointSettings(environment(mapOf("epistola.support.health-check.stale-after" to ""))).check()
        RetiredEndpointSettings(environment(mapOf("epistola.support.hub.discovery-url" to "", "epistola.exchange.base-url" to " "))).check()
    }

    private fun environment(values: Map<String, Any>) = StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("test", values))
    }
}
