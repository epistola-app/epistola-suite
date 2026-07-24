// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.backups

import app.epistola.suite.tenantbackup.TenantBackupArtifact
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * Retention: [TenantBackupStore.pruneToRetention] keeps the N newest backups (by capture time) and
 * deletes the rest — the cap that stops a tenant's backups growing without bound.
 */
class TenantBackupRetentionIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var store: TenantBackupStore

    @Test
    fun `pruneToRetention keeps the N newest backups and reports how many it removed`() {
        val tenant = createTenant("Retention")
        (1..4).forEach { i ->
            store.save(
                TenantBackupArtifact(
                    tenantKey = tenant.id,
                    schemaStamp = "20260101000000",
                    buildVersion = "test",
                    fingerprint = "fp$i",
                    capturedAt = Instant.parse("2026-06-1${i}T00:00:00Z"),
                    tableCount = 1,
                    rowCount = 1,
                    blobCount = 0,
                    bytes = byteArrayOf(i.toByte()),
                ),
            )
        }

        val pruned = store.pruneToRetention(tenant.id, keep = 2)

        assertThat(pruned).isEqualTo(2)
        // list() is newest-first, so the two survivors are the two most recently captured.
        assertThat(store.list(tenant.id).map { it.fingerprint }).containsExactly("fp4", "fp3")
    }
}
