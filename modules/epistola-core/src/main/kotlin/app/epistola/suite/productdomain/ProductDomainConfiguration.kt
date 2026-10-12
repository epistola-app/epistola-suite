// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.productdomain

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration

@Configuration
@EnableConfigurationProperties(ProductDomainProperties::class)
class ProductDomainConfiguration {
    @Bean
    fun productDiscoveryClient(): ProductDiscoveryClient = ProductDiscoveryClient(Duration.ofSeconds(3), Duration.ofSeconds(5))

    @Bean
    fun retiredEndpointSettings(environment: Environment): RetiredEndpointSettings = RetiredEndpointSettings(environment).also { it.check() }
}

/**
 * Refuses to start while a setting ADR 0030 replaced is still set. Silently ignoring it would drop
 * an operator's override on upgrade — their installation would call epistola.app instead of the
 * service they configured — so the old name fails loudly, naming its replacement.
 */
class RetiredEndpointSettings(private val environment: Environment) {
    fun check() {
        val binder = Binder.get(environment)
        val stillSet = RETIRED.filter { (old, _) ->
            !binder.bind(old, String::class.java).orElse(null).isNullOrBlank()
        }
        check(stillSet.isEmpty()) {
            "These settings were replaced (ADR 0030, docs/outbound-traffic.md); rename them and start again:\n" +
                stillSet.entries.joinToString("\n") { (old, new) -> "  $old -> $new" }
        }
    }

    companion object {
        val RETIRED: Map<String, String> = linkedMapOf(
            "epistola.version-check.well-known-url" to "epistola.version-check.endpoint",
            "epistola.installation-check.url" to "epistola.installation-check.endpoint",
            "epistola.exchange.base-url" to "epistola.exchange.endpoint",
            "epistola.exchange.discovery-url" to "epistola.product-domain (Exchange is now found through its discovery document)",
            "epistola.support.hub.host" to "epistola.support.hub.endpoint (host:port)",
            "epistola.support.hub.port" to "epistola.support.hub.endpoint (host:port)",
            "epistola.support.hub.discovery-url" to "epistola.product-domain (the hub is now found through its discovery document)",
        )
    }
}
