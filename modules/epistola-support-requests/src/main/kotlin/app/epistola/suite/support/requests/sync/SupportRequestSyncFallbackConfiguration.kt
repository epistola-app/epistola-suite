// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.support.requests.desk.SupportRequestHandlingProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Fallback configuration that registers a [NoOpSupportRequestSyncAdapter] when no other [SupportRequestSyncPort]
 * bean exists, so feedback works locally whether or not the support connection is on. A real
 * adapter replaces it by being a bean of that type.
 *
 * `@ConditionalOnMissingBean` on a `@Bean` method of a component-scanned configuration sees every
 * scanned `@Component`, but not a `@Bean` from another configuration class processed after this
 * one. So a replacing adapter is a `@Component` class, not a `@Bean` method;
 * `SupportTierContextTest` guards that exactly one port exists.
 *
 * Also registers [SupportRequestSyncProperties] so generic sync settings are always available.
 */
@Configuration
@EnableConfigurationProperties(SupportRequestSyncProperties::class, SupportRequestHandlingProperties::class)
class SupportRequestSyncFallbackConfiguration {

    @Bean
    @ConditionalOnMissingBean(SupportRequestSyncPort::class)
    fun noOpSupportRequestSyncAdapter(): SupportRequestSyncPort = NoOpSupportRequestSyncAdapter()
}
