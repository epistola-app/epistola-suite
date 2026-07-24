// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.snapshots

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.port.InstallationStore
import app.epistola.suite.snapshots.SnapshotSyncPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the hub-backed snapshot sync. Active only when `epistola.support.enabled=true` (same gate
 * as the rest of the support tier); the [EpistolaHubClient] it injects is provided by
 * `epistola-support`'s `SupportConfiguration`.
 *
 * Registering [HubSnapshotSyncAdapter] as the `SnapshotSyncPort` bean overrides the no-op fallback,
 * so snapshots sync to the hub only when the support tier is enabled.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "epistola.support",
    name = ["enabled"],
    havingValue = "true",
)
class SupportSnapshotsConfiguration {
    @Bean
    fun snapshotSyncPort(
        client: EpistolaHubClient,
        installationStore: InstallationStore,
    ): SnapshotSyncPort = HubSnapshotSyncAdapter(client, installationStore)
}
