// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenantbackup

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * A backup must not be produced while a tenant still has un-migrated assets
 * (`content_hash IS NULL`), whose bytes live only in the legacy `content_store` and are
 * no longer archived — it would be silently unfaithful. The cycle is skipped until the
 * #738 backfill drains the tenant (transitional; removed with #742).
 */
class BackupSkipsUnmigratedAssetsIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbi: Jdbi

    @Test
    fun `skips the backup while a tenant has an un-migrated asset`() {
        val tenant = createTenant("Unmigrated Assets")
        val cat = CatalogKey.of("main")
        withMediator { CreateCatalog(tenantKey = tenant.id, id = cat, name = "Main").execute() }

        // Simulate a not-yet-backfilled asset (content_hash NULL) — bytes would only be in
        // the legacy content_store, which the dump no longer archives.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO assets (id, tenant_key, catalog_key, name, media_type, size_bytes, width, height, content_hash, created_at)
                VALUES (:id, :tenant, :cat, 'legacy.png', 'image/png', 3, 1, 1, NULL, now())
                """,
            )
                .bind("id", UUID.randomUUID())
                .bind("tenant", tenant.id)
                .bind("cat", cat)
                .execute()
        }

        val backup = withMediator { BuildTenantBackup(tenant.id).execute() }
        assertThat(backup)
            .`as`("backup should be skipped while an asset is un-migrated")
            .isNull()
    }
}
