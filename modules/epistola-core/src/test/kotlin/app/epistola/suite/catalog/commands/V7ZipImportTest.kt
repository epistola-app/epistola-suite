// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogMigrationConfirmationRequiredException
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A catalog ZIP from wire v7 or earlier names no registry or namespace, which are its name: the
 * import is told both, never invents them (contract ADR 0003), and the author confirms migrating
 * authored content. A v8 archive names its own, and an import never renames it.
 */
class V7ZipImportTest : IntegrationTestBase() {

    @Autowired
    private lateinit var identity: CatalogIdentityProperties

    @Test
    fun `a wire-v7 ZIP needs its registry and namespace named, then the migration confirmed, and lands under that name`() {
        val tenant = createTenant("V7 zip import")

        withMediator {
            assertThatThrownBy { ImportCatalogZip(tenant.id, v7Archive(), confirmMigration = true, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }
                .isInstanceOfSatisfying(CatalogNameRequiredException::class.java) { assertThat(it.missing).containsExactlyInAnyOrder("registry", "namespace") }
            assertThatThrownBy { ImportCatalogZip(tenant.id, v7Archive(), registry = identity.defaultRegistry, namespace = "letters-ns", provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }
                .isInstanceOf(CatalogMigrationConfirmationRequiredException::class.java)

            val result = ImportCatalogZip(tenant.id, v7Archive(), confirmMigration = true, registry = identity.defaultRegistry, namespace = "letters-ns", provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThat(result.results.map { it.status }).containsOnly(InstallStatus.INSTALLED)
            val catalog = GetCatalog(testCatalog(tenant.id, CatalogKey.of("letters"))).query()!!
            assertThat(catalog.namespace).isEqualTo("letters-ns")
            assertThat(catalog.registry).isEqualTo(identity.defaultRegistry)
        }
    }

    @Test
    fun `a wire-v8 ZIP keeps the name it has, and naming another is refused`() {
        val producer = createTenant("V8 zip producer").id
        val consumer = createTenant("V8 zip consumer").id
        val catalog = CatalogKey.of("v8-named")
        val archive = withMediator {
            TestNamespaces.grant(producer, "v8-named-ns").execute()
            CreateCatalog(producer, catalog, "V8 named", namespace = "v8-named-ns").execute()
            mediator.exportReleased(testCatalog(app.epistola.suite.common.ids.TenantId(producer), catalog)).zipBytes
        }

        withMediator {
            assertThatThrownBy { ImportCatalogZip(consumer, archive, namespace = "another-ns", provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }
                .isInstanceOfSatisfying(CatalogNameMismatchException::class.java) { assertThat(it.named).isEqualTo("v8-named-ns") }
            assertThatThrownBy { ImportCatalogZip(consumer, archive, registry = "registry.elsewhere.example", provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }
                .isInstanceOf(CatalogNameMismatchException::class.java)
            assertThat(GetCatalog(testCatalog(consumer, catalog)).query()).isNull()

            ImportCatalogZip(consumer, archive, registry = identity.defaultRegistry, namespace = "v8-named-ns", provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            assertThat(GetCatalog(testCatalog(consumer, catalog)).query()!!.namespace).isEqualTo("v8-named-ns")
        }
    }

    /** A v7 catalog holding one theme, addressed by `slug` as v7 did. */
    private fun v7Archive(): ByteArray {
        val manifest = """
            {"schemaVersion":7,
             "catalog":{"slug":"letters","name":"Letters"},
             "publisher":{"name":"Test"},
             "release":{"version":"1.0.0"},
             "resources":[{"type":"theme","slug":"brand","name":"Brand","detailUrl":"./resources/theme/brand.json"}]}
        """.trimIndent()
        val theme = """
            {"schemaVersion":7,"resource":{"type":"theme","slug":"brand","name":"Brand","documentStyles":{}}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, content) in listOf("catalog.json" to manifest, "resources/theme/brand.json" to theme)) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
