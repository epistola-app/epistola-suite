package app.epistola.suite.backups

import app.epistola.suite.common.ids.TenantKey
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Fallback [BackupSyncPort] used when the support tier is disabled (no hub adapter wired). It
 * reports itself disabled so the scheduler and UI never attempt remote calls; list methods return
 * empty and the byte-moving methods are never reached (callers gate on [isEnabled]).
 */
class NoOpBackupSyncAdapter : BackupSyncPort {
    override fun isEnabled(): Boolean = false

    override fun uploadSnapshot(snapshot: TenantSnapshot): SnapshotUploadResult = error("Backup sync target is not configured (epistola.support.enabled=false)")

    override fun listSnapshots(tenantKey: TenantKey): List<RemoteSnapshot> = emptyList()

    override fun downloadSnapshot(
        tenantKey: TenantKey,
        snapshotId: String,
    ): ByteArray = error("Backup sync target is not configured (epistola.support.enabled=false)")
}

/** Registers the no-op adapter when no other [BackupSyncPort] (i.e. the hub adapter) is present. */
@Configuration
class BackupSyncFallbackConfiguration {
    @Bean
    @ConditionalOnMissingBean(BackupSyncPort::class)
    fun noOpBackupSyncAdapter(): BackupSyncPort = NoOpBackupSyncAdapter()
}
