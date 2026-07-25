// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.feedback

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.port.InstallationStore
import app.epistola.suite.feedback.sync.FeedbackSyncPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the hub-backed feedback sync. Active only when `epistola.support.enabled=true`
 * (same gate as the rest of the support tier); the [EpistolaHubClient] bean it injects is
 * provided by `epistola-support`'s `SupportConfiguration`.
 *
 * Registering [HubFeedbackSyncAdapter] as the `FeedbackSyncPort` bean overrides the feedback
 * module's `@ConditionalOnMissingBean` no-op fallback, so feedback syncs to the hub only when
 * the support tier is enabled.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "epistola.support",
    name = ["enabled"],
    havingValue = "true",
)
class SupportFeedbackConfiguration {
    @Bean
    fun feedbackSyncPort(
        client: EpistolaHubClient,
        installationStore: InstallationStore,
    ): FeedbackSyncPort = HubFeedbackSyncAdapter(client, installationStore)
}
