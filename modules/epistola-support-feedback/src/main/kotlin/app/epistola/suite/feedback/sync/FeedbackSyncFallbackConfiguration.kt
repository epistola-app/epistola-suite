// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.feedback.sync

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the [NoOpFeedbackSyncAdapter], whether or not the support connection is on: nothing sends
 * feedback anywhere yet. `@ConditionalOnMissingBean` lets an explicit or test override win.
 *
 * Also registers [FeedbackSyncProperties] so generic sync settings are always available.
 */
@Configuration
@EnableConfigurationProperties(FeedbackSyncProperties::class)
class FeedbackSyncFallbackConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeedbackSyncPort::class)
    fun noOpFeedbackSyncAdapter(): FeedbackSyncPort = NoOpFeedbackSyncAdapter()
}
