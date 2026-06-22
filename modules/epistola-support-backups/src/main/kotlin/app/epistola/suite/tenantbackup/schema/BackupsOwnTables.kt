package app.epistola.suite.tenantbackup.schema

import app.epistola.suite.backup.TenantBackupTableContributor
import org.springframework.stereotype.Component

/**
 * Classifies the backups module's own tenant-scoped table: `tenant_backups` (the local backup store)
 * is **excluded** — never back up the backups, which would be recursive. Declared via the same
 * contributor SPI as every other module, so `TenantTableTopology` holds no hard-coded table names.
 */
@Component
class BackupsOwnTables : TenantBackupTableContributor {
    override fun excludedTables(): Set<String> = setOf("tenant_backups")
}
