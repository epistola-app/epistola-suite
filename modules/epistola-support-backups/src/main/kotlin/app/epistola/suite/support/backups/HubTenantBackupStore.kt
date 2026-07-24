// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.backups

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.proto.v1.BackupHeader
import app.epistola.hub.proto.v1.BackupSummary
import app.epistola.hub.proto.v1.DownloadBackupRequest
import app.epistola.hub.proto.v1.ListBackupsRequest
import app.epistola.suite.backups.StoredBackup
import app.epistola.suite.backups.TenantBackupStore
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.tenantbackup.TenantBackupArtifact
import com.google.protobuf.Timestamp
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Hub-backed [TenantBackupStore] — active when the support tier is enabled
 * (`epistola.support.enabled=true`), replacing the local [app.epistola.suite.backups.JdbiTenantBackupStore].
 * Backups stream to epistola-hub over gRPC ([EpistolaHubClient.uploadBackup] /
 * `listBackups` / `downloadBackup`), where they are stored fingerprint-deduped, separate from the
 * catalog snapshots the Upgrading feature ships. Retention is the hub's concern (it prunes on upload),
 * so [pruneToRetention] is a no-op here.
 *
 * Hub errors (`HubUnavailableException`, `HubEntitlementDeniedException`, `HubUnauthenticatedException`)
 * propagate to the caller — the scheduler and UI handler already surface them.
 */
@Component
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class HubTenantBackupStore(
    private val hub: EpistolaHubClient,
) : TenantBackupStore {
    override fun save(artifact: TenantBackupArtifact): String {
        val header =
            BackupHeader
                .newBuilder()
                .setTenant(artifact.tenantKey.value)
                .setFingerprint(artifact.fingerprint)
                .setSchemaStamp(artifact.schemaStamp)
                .setBuildVersion(artifact.buildVersion)
                .setTableCount(artifact.tableCount)
                .setRowCount(artifact.rowCount)
                .setBlobCount(artifact.blobCount)
                .setCapturedAt(artifact.capturedAt.toTimestamp())
                .build()
        return hub.uploadBackup(header, artifact.bytes).backupId
    }

    override fun list(tenantKey: TenantKey): List<StoredBackup> = hub
        .listBackups(ListBackupsRequest.newBuilder().setTenant(tenantKey.value).build())
        .backupsList
        .map { it.toStoredBackup() }

    override fun load(
        tenantKey: TenantKey,
        backupId: String,
    ): ByteArray? = hub
        .downloadBackup(
            DownloadBackupRequest.newBuilder().setTenant(tenantKey.value).setBackupId(backupId).build(),
        ).content

    /** The hub prunes on upload, so there is nothing to do suite-side. */
    override fun pruneToRetention(
        tenantKey: TenantKey,
        keep: Int,
    ): Int = 0

    private fun BackupSummary.toStoredBackup(): StoredBackup = StoredBackup(
        id = backupId,
        fingerprint = fingerprint,
        schemaStamp = schemaStamp,
        buildVersion = buildVersion,
        tableCount = tableCount,
        rowCount = rowCount,
        blobCount = blobCount,
        sizeBytes = sizeBytes,
        capturedAt = capturedAt.toInstant(),
        createdAt = createdAt.toInstant(),
    )

    private fun Instant.toTimestamp(): Timestamp = Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()

    private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())
}
