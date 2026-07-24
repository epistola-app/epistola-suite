// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.backups

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Exercises the local backups engine: fingerprint dedup (an unchanged tenant stores nothing) and a
 * store round-trip (restoring an older backup reverts the tenant, dropping later changes).
 */
class TenantBackupServiceIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var backupService: TenantBackupService

    @Test
    fun `stores once, dedups when unchanged, and stores again on change`() {
        val tenant = createTenant("Backup Dedup")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("alpha"), "Alpha").execute() }

        val first = withMediator { backupService.backupTenant(tenant.id) }
        assertThat(first).isInstanceOf(BackupOutcome.Created::class.java)
        assertThat(withMediator { backupService.listBackups(tenant.id) }).hasSize(1)

        val unchanged = withMediator { backupService.backupTenant(tenant.id) }
        assertThat(unchanged).isInstanceOf(BackupOutcome.Unchanged::class.java)
        assertThat(withMediator { backupService.listBackups(tenant.id) }).hasSize(1)

        withMediator { CreateCatalog(tenant.id, CatalogKey.of("beta"), "Beta").execute() }
        val changed = withMediator { backupService.backupTenant(tenant.id) }
        assertThat(changed).isInstanceOf(BackupOutcome.Created::class.java)
        assertThat(withMediator { backupService.listBackups(tenant.id) }).hasSize(2)
    }

    @Test
    fun `restoring an older backup reverts the tenant`() {
        val tenant = createTenant("Backup Restore")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("alpha"), "Alpha").execute() }
        val firstBackupId = (withMediator { backupService.backupTenant(tenant.id) } as BackupOutcome.Created).backupId

        withMediator { CreateCatalog(tenant.id, CatalogKey.of("beta"), "Beta").execute() }
        assertThat(withMediator { ListCatalogs(tenant.id).query() }.map { it.id.value }).contains("alpha", "beta")

        withMediator { backupService.restoreFromBackup(tenant.id, firstBackupId) }

        assertThat(withMediator { ListCatalogs(tenant.id).query() }.map { it.id.value })
            .contains("alpha")
            .doesNotContain("beta")
    }
}
