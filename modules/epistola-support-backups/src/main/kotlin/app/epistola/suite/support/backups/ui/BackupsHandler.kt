package app.epistola.suite.support.backups.ui

import app.epistola.suite.backups.BackupOutcome
import app.epistola.suite.backups.StoredBackup
import app.epistola.suite.backups.TenantBackupService
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.tenants.queries.GetTenant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * UI handler for the Support → Backups page: lists a tenant's locally-stored faithful backups,
 * triggers an on-demand backup, and restores one. Visible only when the `support-backups` feature
 * toggle is on (nav) and gated per-action on `TENANT_SETTINGS`. Backups are stored locally, so there
 * is no hub round-trip here.
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

        val backups = backupService.listBackups(tenantId.key)
        return ServerResponse.ok().page("backups/list") {
            "pageTitle" to "Backups - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "activeNavSection" to "backups"
            "backups" to backups.mapIndexed { index, backup -> backup.toView(isLatest = index == 0) }
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

    private fun StoredBackup.toView(isLatest: Boolean): BackupView = BackupView(
        backupId = id,
        capturedAt = FORMATTER.format(capturedAt.atOffset(ZoneOffset.UTC)),
        size = humanSize(sizeBytes),
        tableCount = tableCount,
        rowCount = rowCount,
        buildVersion = buildVersion.ifBlank { "—" },
        isLatest = isLatest,
    )

    data class BackupView(
        val backupId: String,
        val capturedAt: String,
        val size: String,
        val tableCount: Int,
        val rowCount: Int,
        val buildVersion: String,
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
