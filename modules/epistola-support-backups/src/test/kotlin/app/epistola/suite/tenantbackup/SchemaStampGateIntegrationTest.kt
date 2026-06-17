package app.epistola.suite.tenantbackup

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup whose schema stamp does not match the running schema is refused **before any write** —
 * the guarantee that a faithful restore is a same-schema undo, never a cross-version migration.
 */
class SchemaStampGateIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var crypto: TenantBackupCrypto

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `restore refuses a backup from a different schema and mutates nothing`() {
        val tenant = createTenant("Stamp Gate")
        withMediator { CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("main"), name = "Main").execute() }

        val backup = withMediator { BuildTenantBackup(tenant.id).execute() }
        val tampered = retag(backup.bytes, "00000000000000")

        assertThatThrownBy { withMediator { RestoreTenantBackup(tenant.id, tampered).execute() } }
            .hasMessageContaining("same schema")

        // The refusal happened before any mutation: the tenant's catalogs are untouched.
        withMediator {
            assertThat(ListCatalogs(tenant.id).query().map { it.id.value }).contains("main")
        }
    }

    /** Re-wraps the artifact with the manifest's `schemaStamp` replaced. */
    private fun retag(
        artifactBytes: ByteArray,
        stamp: String,
    ): ByteArray {
        val archive = crypto.unwrap(artifactBytes)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { out ->
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val content = zip.readBytes()
                    out.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "backup.json") {
                        val manifest = objectMapper.readValue(content, TenantBackupManifest::class.java)
                        out.write(objectMapper.writeValueAsBytes(manifest.copy(schemaStamp = stamp)))
                    } else {
                        out.write(content)
                    }
                    out.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return crypto.wrap(baos.toByteArray())
    }
}
