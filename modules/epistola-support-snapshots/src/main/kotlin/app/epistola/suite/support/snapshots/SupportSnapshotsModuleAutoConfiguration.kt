package app.epistola.suite.support.snapshots

import app.epistola.suite.snapshots.SnapshotSyncFallbackConfiguration
import app.epistola.suite.snapshots.TenantSnapshotSyncService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Import

@AutoConfiguration
@ConditionalOnSupportSnapshotsModule
@Import(
    SupportSnapshotsConfiguration::class,
    SnapshotSyncFallbackConfiguration::class,
    TenantSnapshotSyncService::class,
)
class SupportSnapshotsModuleAutoConfiguration
