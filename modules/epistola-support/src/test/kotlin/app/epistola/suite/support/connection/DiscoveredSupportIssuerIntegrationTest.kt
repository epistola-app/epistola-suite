// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.mediator.execute
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.productdomain.ProductDiscovery
import app.epistola.suite.productdomain.ProductDiscoveryClient
import app.epistola.suite.productdomain.ProductDomainProperties
import app.epistola.suite.productdomain.ProductEndpoints
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI

/**
 * With `epistola.support.endpoint` blank, Epistola support's connection is at the issuer the product
 * domain's discovery document names (`support.issuer`, `docs/support-protocol.md` "Finding the
 * service"), not at `https://support.{product-domain}`: it is stored there and registers there.
 */
@Import(DiscoveredSupportIssuerIntegrationTest.DiscoveryNamingTheFake::class)
class DiscoveredSupportIssuerIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connections: SupportConnections

    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Test
    fun `Epistola support's connection is stored and registers at the issuer the discovery document names`() {
        assertThat(connections.configuredEndpoint()).isEqualTo(URI(support.baseUrl))
        assertThat(connection.settings.issuer).isEqualTo(URI(support.baseUrl))

        val id = withMediator { EnsureConfiguredSupportConnection().execute() }
        assertThat(connections.find(id)!!.endpoint).isEqualTo(support.baseUrl)
        assertThat(registry.find(id)).`as`("the stored row is the connection every module injects").isSameAs(connection)

        connection.checkNow()

        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Pending::class.java)
        assertThat(connection.status().issuer).isEqualTo(URI(support.baseUrl))
        assertThat(support.registrationRequests).hasSize(1)
    }

    /** Product discovery naming the stand-in service as the support issuer, as epistola.app names Connect. */
    @TestConfiguration
    class DiscoveryNamingTheFake {
        @Bean
        @Primary
        fun discoveryNamingTheFake(domain: ProductDomainProperties, metadata: AppMetadataService, client: ProductDiscoveryClient): ProductDiscovery = object : ProductDiscovery(domain, metadata, client) {
            override fun endpoints(): ProductEndpoints = ProductEndpoints.defaults(domain).copy(support = ProductEndpoints.SupportEndpoint(URI(support.baseUrl)))
        }
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            // Deliberately no epistola.support.endpoint: this is the discovery path.
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.auto-register") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}
