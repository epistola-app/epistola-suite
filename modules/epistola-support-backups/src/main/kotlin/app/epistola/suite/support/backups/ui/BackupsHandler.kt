package app.epistola.suite.support.backups.ui

import app.epistola.hub.client.error.HubEntitlementDeniedException
import app.epistola.hub.client.error.HubUnavailableException
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.snapshots.RemoteSnapshot
import app.epistola.suite.snapshots.SnapshotSyncOutcome
import app.epistola.suite.snapshots.TenantSnapshotSyncService
import app.epistola.suite.support.hubFeatureCall
import app.epistola.suite.support.logTo
import app.epistola.suite.tenants.queries.GetTenant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * UI handler for the Support → Backups page: lists a tenant's catalog snapshots on the hub,
 * triggers an on-demand backup, and restores a snapshot. Visible only when the `support-backups`
 * feature toggle is on (nav) and gated per-action on `TENANT_SETTINGS`. Hub-entitlement failures
 * (no service contract) render an informational state rather than an error.
 */
@Component
class BackupsHandler(
    private val snapshotSync: TenantSnapshotSyncService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()

        // One status to render, instead of a 500: OK / UNAVAILABLE / NOT_ENTITLED / ERROR.
        val result = hubFeatureCall { snapshotSync.listSnapshots(tenantId.key).map { it.toView() } }
        result.logTo(log, tenantId.key.value, "backups")

        return ServerResponse.ok().page("backups/list") {
            "pageTitle" to "Backups - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "activeNavSection" to "backups"
            "status" to result.status.name
            "snapshots" to (result.value ?: emptyList<SnapshotView>())
            "saved" to request.param("saved").orElse(null)
            "error" to request.param("error").orElse(null)
        }
    }

    fun backupNow(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        return try {
            // Reflect the real outcome: a fresh upload vs. dedup (catalogs unchanged since the last
            // backup, so nothing new is stored) — otherwise "Backup completed" misleads.
            val query =
                when (val outcome = snapshotSync.syncTenant(tenantId.key)) {
                    is SnapshotSyncOutcome.Uploaded -> if (outcome.deduplicated) "saved=backup-unchanged" else "saved=backup"
                    is SnapshotSyncOutcome.Unchanged -> "saved=backup-unchanged"
                    SnapshotSyncOutcome.Disabled, SnapshotSyncOutcome.NotReady -> "error=hub-unavailable"
                }
            redirect(tenantId.key.value, query)
        } catch (e: HubEntitlementDeniedException) {
            redirect(tenantId.key.value, "error=not-entitled")
        } catch (e: HubUnavailableException) {
            log.warn("Backup for tenant {} could not reach the Epistola hub: {}", tenantId.key.value, e.message)
            redirect(tenantId.key.value, "error=hub-unavailable")
        } catch (e: Exception) {
            log.error("Manual backup failed for tenant {}: {}", tenantId.key.value, e.message, e)
            redirect(tenantId.key.value, "error=backup-failed")
        }
    }

    fun restore(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val snapshotId = request.pathVariable("snapshotId")
        return try {
            snapshotSync.restoreFromSnapshot(tenantId.key, snapshotId)
            redirect(tenantId.key.value, "saved=restore")
        } catch (e: HubEntitlementDeniedException) {
            redirect(tenantId.key.value, "error=not-entitled")
        } catch (e: HubUnavailableException) {
            log.warn("Restore for tenant {} could not reach the Epistola hub: {}", tenantId.key.value, e.message)
            redirect(tenantId.key.value, "error=hub-unavailable")
        } catch (e: Exception) {
            log.error("Restore failed for tenant {} from snapshot {}: {}", tenantId.key.value, snapshotId, e.message, e)
            redirect(tenantId.key.value, "error=restore-failed")
        }
    }

    // Both actions are triggered via htmx (a boosted form / the confirm dialog). Return 200 with
    // HX-Redirect so htmx navigates the whole page — a 303 would be followed transparently by the
    // XHR and the HX-Redirect header would be lost.
    private fun redirect(
        tenantKey: String,
        query: String,
    ): ServerResponse = ServerResponse.ok().header("HX-Redirect", "/tenants/$tenantKey/backups?$query").build()

    private fun RemoteSnapshot.toView(): SnapshotView = SnapshotView(
        snapshotId = snapshotId,
        capturedAt = FORMATTER.format(capturedAt.atOffset(ZoneOffset.UTC)),
        size = humanSize(sizeBytes),
        catalogCount = catalogCount,
        suiteVersion = suiteVersion.ifBlank { "—" },
        isLatest = isLatest,
    )

    data class SnapshotView(
        val snapshotId: String,
        val capturedAt: String,
        val size: String,
        val catalogCount: Int,
        val suiteVersion: String,
        val isLatest: Boolean,
    )

    private companion object {
        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")

        fun humanSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
