// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.snapshot

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Builds the shared tenant snapshot primitive that backups and compatibility checks both ride.
 * Verifies the archive shape (system catalog excluded, authored catalogs bundled byte-for-byte)
 * and that the rolled-up fingerprint is deterministic across rebuilds of unchanged catalogs (the
 * property dedup relies on).
 */
class BuildTenantSnapshotIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `snapshot excludes the system catalog and bundles the authored catalogs`() {
        val tenant = createTenant("Snapshot Bundle")
        withMediator {
            CreateCatalog(tenant.id, CatalogKey.of("alpha"), "Alpha").execute()
            CreateCatalog(tenant.id, CatalogKey.of("beta"), "Beta").execute()
        }

        val snapshot = withMediator { BuildTenantSnapshot(tenant.id).execute() }

        // The created tenant also has the bootstrap system catalog — it must NOT be in the snapshot.
        // (It also gets a tenant-owned `default` catalog, which IS backed up.)
        val manifest = readManifest(snapshot.bytes)
        val entries = manifest.catalogs
        assertThat(entries.map { it.catalogKey }).contains("alpha", "beta")
        assertThat(entries.map { it.catalogKey }).doesNotContain(SYSTEM_CATALOG_KEY.value)
        assertThat(snapshot.catalogCount).isEqualTo(entries.size)

        // The running suite version is recorded (no BuildProperties in tests → "dev").
        assertThat(manifest.suiteVersion).isEqualTo("dev")
        assertThat(snapshot.suiteVersion).isEqualTo("dev")

        val zipPaths = zipEntryNames(snapshot.bytes)
        assertThat(zipPaths).contains("snapshot.json", "catalogs/alpha.zip", "catalogs/beta.zip")
    }

    @Test
    fun `the rolled-up fingerprint is stable across rebuilds of unchanged catalogs`() {
        val tenant = createTenant("Stable Fingerprint")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("alpha"), "Alpha").execute() }

        val first = withMediator { BuildTenantSnapshot(tenant.id).execute() }.snapshotFingerprint
        val second = withMediator { BuildTenantSnapshot(tenant.id).execute() }.snapshotFingerprint

        assertThat(second).isEqualTo(first)
    }

    private fun readManifest(bytes: ByteArray): SnapshotManifest {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "snapshot.json") {
                    return objectMapper.readValue(zip.readBytes(), SnapshotManifest::class.java)
                }
                entry = zip.nextEntry
            }
        }
        error("snapshot.json not found in archive")
    }

    private fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
    }
}
