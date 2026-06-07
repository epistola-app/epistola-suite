package app.epistola.suite.support.backups

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.port.InstallationStore
import app.epistola.suite.backups.BackupSyncPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the hub-backed catalog-backup sync. Active only when `epistola.support.enabled=true`
 * (same gate as the rest of the support tier); the [EpistolaHubClient] it injects is provided by
 * `epistola-support`'s `SupportConfiguration`.
 *
 * Registering [HubBackupSyncAdapter] as the `BackupSyncPort` bean overrides the no-op fallback,
 * so snapshots sync to the hub only when the support tier is enabled.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "epistola.support",
    name = ["enabled"],
    havingValue = "true",
)
class SupportBackupsConfiguration {
    @Bean
    fun backupSyncPort(
        client: EpistolaHubClient,
        installationStore: InstallationStore,
    ): BackupSyncPort = HubBackupSyncAdapter(client, installationStore)
}
