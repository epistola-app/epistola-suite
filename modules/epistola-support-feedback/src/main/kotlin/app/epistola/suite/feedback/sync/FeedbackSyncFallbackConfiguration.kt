// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.feedback.sync

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Fallback configuration that registers a [NoOpFeedbackSyncAdapter] when the support tier is **off**.
 * Gated on the inverse of the hub adapter's `epistola.support.enabled=true` so the two are mutually
 * exclusive regardless of configuration-class ordering (an `@ConditionalOnMissingBean`-only fallback
 * can race the hub adapter and register both beans). `@ConditionalOnMissingBean` is kept so an
 * explicit/test override still wins.
 *
 * Also registers [FeedbackSyncProperties] so generic sync settings are always available.
 */
@Configuration
@EnableConfigurationProperties(FeedbackSyncProperties::class)
class FeedbackSyncFallbackConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeedbackSyncPort::class)
    @ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun noOpFeedbackSyncAdapter(): FeedbackSyncPort = NoOpFeedbackSyncAdapter()
}
