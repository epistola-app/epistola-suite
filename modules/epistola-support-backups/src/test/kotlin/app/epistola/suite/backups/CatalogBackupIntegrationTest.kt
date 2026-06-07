package app.epistola.suite.backups

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

@Import(CatalogBackupIntegrationTest.TestConfig::class)
class CatalogBackupIntegrationTest : IntegrationTestBase() {
    class TestConfig {
        @Bean
        @Primary
        fun recordingBackupSyncPort(): BackupSyncPort = RecordingBackupSyncPort()
    }

    @Autowired
    lateinit var syncPort: BackupSyncPort

    @Autowired
    lateinit var backupService: CatalogBackupService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private val recording get() = syncPort as RecordingBackupSyncPort

    @BeforeEach
    fun resetRecordingPort() {
        recording.reset()
    }

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
        val entries = readManifest(snapshot.bytes).catalogs
        assertThat(entries.map { it.catalogKey }).contains("alpha", "beta")
        assertThat(entries.map { it.catalogKey }).doesNotContain(SYSTEM_CATALOG_KEY.value)
        assertThat(snapshot.catalogCount).isEqualTo(entries.size)

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

    @Test
    fun `backup uploads once then skips the upload while catalogs are unchanged`() {
        val tenant = createTenant("Dedup")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("alpha"), "Alpha").execute() }

        val first = runAs(backupSystemPrincipal(tenant.id)) { backupService.backupTenant(tenant.id) }
        assertThat(first).isInstanceOf(BackupOutcome.Uploaded::class.java)
        assertThat(recording.uploads).hasSize(1)

        val second = runAs(backupSystemPrincipal(tenant.id)) { backupService.backupTenant(tenant.id) }
        assertThat(second).isInstanceOf(BackupOutcome.Unchanged::class.java)
        // No second upload — the suite-side fingerprint cache short-circuited it.
        assertThat(recording.uploads).hasSize(1)
    }

    @Test
    fun `a new catalog changes the fingerprint and triggers a fresh upload`() {
        val tenant = createTenant("Changed")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("alpha"), "Alpha").execute() }
        runAs(backupSystemPrincipal(tenant.id)) { backupService.backupTenant(tenant.id) }

        withMediator { CreateCatalog(tenant.id, CatalogKey.of("beta"), "Beta").execute() }
        val outcome = runAs(backupSystemPrincipal(tenant.id)) { backupService.backupTenant(tenant.id) }

        assertThat(outcome).isInstanceOf(BackupOutcome.Uploaded::class.java)
        assertThat(recording.uploads).hasSize(2)
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
