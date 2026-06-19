package app.epistola.suite.support.backups.ui

import app.epistola.hub.client.error.HubEntitlementDeniedException
import app.epistola.hub.client.error.HubException
import app.epistola.hub.client.error.HubUnauthenticatedException
import app.epistola.suite.backups.BackupOutcome
import app.epistola.suite.backups.StoredBackup
import app.epistola.suite.backups.TenantBackupService
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.tenantbackup.IncompatibleBackupSchemaException
import app.epistola.suite.tenants.queries.GetTenant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * UI handler for the Support → Backups page: lists a tenant's faithful backups, triggers an
 * on-demand backup, and restores one. Visible only when the `support-backups` feature toggle is on
 * (nav) and gated per-action on `TENANT_SETTINGS`.
 *
 * Each backup is annotated with whether it is **restore-compatible** with the running schema
 * (computed by [TenantBackupService.restorability]) so an incompatible one is shown as such (and its
 * Restore disabled) rather than failing with a generic error on click. Hub round-trips (the
 * hub-backed store) degrade to a notice instead of a 500 when the hub is down or unentitled.
 */
@Component
class BackupsHandler(
    private val backupService: TenantBackupService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()

        // A managed-services feature must not 500 the page when the hub is transiently down or the
        // installation isn't entitled — degrade to a notice with an empty list.
        var hubStatus = "OK"
        val backups =
            try {
                backupService.listBackups(tenantId.key)
            } catch (e: HubEntitlementDeniedException) {
                hubStatus = "NOT_ENTITLED"
                emptyList()
            } catch (e: HubUnauthenticatedException) {
                log.warn("Hub rejected this installation's credentials listing backups for tenant {}: {}", tenantId.key.value, e.message)
                hubStatus = "AUTH"
                emptyList()
            } catch (e: HubException) {
                log.warn("Could not list backups for tenant {}: {}", tenantId.key.value, e.message)
                hubStatus = "UNAVAILABLE"
                emptyList()
            }
        val restorability = if (backups.isEmpty()) emptyMap() else backupService.restorability(tenantId.key, backups)

        return ServerResponse.ok().page("backups/list") {
            "pageTitle" to "Backups - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "activeNavSection" to "backups"
            "hubStatus" to hubStatus
            "backups" to
                backups.mapIndexed { index, backup ->
                    backup.toView(isLatest = index == 0, restorable = restorability.getValue(backup.id))
                }
            "saved" to request.param("saved").orElse(null)
            "error" to request.param("error").orElse(null)
        }
    }

    fun backupNow(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        return try {
            val query =
                when (backupService.backupTenant(tenantId.key)) {
                    is BackupOutcome.Created -> "saved=backup"
                    is BackupOutcome.Unchanged -> "saved=backup-unchanged"
                }
            redirect(tenantId.key.value, query)
        } catch (e: HubEntitlementDeniedException) {
            log.warn("Backup not entitled for tenant {}: {}", tenantId.key.value, e.message)
            redirect(tenantId.key.value, "error=not-entitled")
        } catch (e: HubUnauthenticatedException) {
            log.warn("Hub rejected this installation's credentials backing up tenant {}: {}", tenantId.key.value, e.message)
            redirect(tenantId.key.value, "error=hub-auth")
        } catch (e: HubException) {
            log.warn("Backup could not reach the hub for tenant {}: {}", tenantId.key.value, e.message)
            redirect(tenantId.key.value, "error=hub-unavailable")
        } catch (e: Exception) {
            log.error("Manual backup failed for tenant {}: {}", tenantId.key.value, e.message, e)
            redirect(tenantId.key.value, "error=backup-failed")
        }
    }

    fun restore(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val backupId = request.pathVariable("backupId")
        return try {
            backupService.restoreFromBackup(tenantId.key, backupId)
            redirect(tenantId.key.value, "saved=restore")
        } catch (e: IncompatibleBackupSchemaException) {
            log.warn("Restore refused for tenant {} from backup {}: {}", tenantId.key.value, backupId, e.reason)
            redirect(tenantId.key.value, "error=restore-incompatible")
        } catch (e: HubEntitlementDeniedException) {
            log.warn("Restore not entitled for tenant {}: {}", tenantId.key.value, e.message)
            redirect(tenantId.key.value, "error=not-entitled")
        } catch (e: HubUnauthenticatedException) {
            log.warn("Hub rejected this installation's credentials restoring tenant {} backup {}: {}", tenantId.key.value, backupId, e.message)
            redirect(tenantId.key.value, "error=hub-auth")
        } catch (e: HubException) {
            log.warn("Restore could not reach the hub for tenant {} backup {}: {}", tenantId.key.value, backupId, e.message)
            redirect(tenantId.key.value, "error=hub-unavailable")
        } catch (e: Exception) {
            log.error("Restore failed for tenant {} from backup {}: {}", tenantId.key.value, backupId, e.message, e)
            redirect(tenantId.key.value, "error=restore-failed")
        }
    }

    // Both actions are triggered via htmx; return 200 with HX-Redirect so htmx navigates the whole
    // page — a 303 would be followed transparently by the XHR and the HX-Redirect header would be lost.
    private fun redirect(
        tenantKey: String,
        query: String,
    ): ServerResponse = ServerResponse.ok().header("HX-Redirect", "/tenants/$tenantKey/backups?$query").build()

    private fun StoredBackup.toView(
        isLatest: Boolean,
        restorable: TenantBackupService.Restorable,
    ): BackupView = BackupView(
        backupId = id,
        capturedAt = FORMATTER.format(capturedAt.atOffset(ZoneOffset.UTC)),
        size = humanSize(sizeBytes),
        tableCount = tableCount,
        rowCount = rowCount,
        buildVersion = buildVersion.ifBlank { "—" },
        isLatest = isLatest,
        restorable = restorable.restorable,
        versionNote = restorable.note,
    )

    data class BackupView(
        val backupId: String,
        val capturedAt: String,
        val size: String,
        val tableCount: Int,
        val rowCount: Int,
        val buildVersion: String,
        val isLatest: Boolean,
        val restorable: Boolean,
        val versionNote: String?,
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
