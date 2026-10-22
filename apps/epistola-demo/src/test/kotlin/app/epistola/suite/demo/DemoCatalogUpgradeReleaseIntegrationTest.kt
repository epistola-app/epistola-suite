// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.releases.NextVersionChanges
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A catalog received from its source, as the demo catalog ships (a default theme, presets, templates):
 * the release an install records keeps the content its archive holds, which is what it is named by
 * (its content hash, ADR 0034), and the next version starts from that release with no changes of its
 * own, so the source's next release can be taken by an upgrade.
 */
// The upgrade's source is a copy in a temporary directory: file: sources are allowed there.
@TestPropertySource(properties = ["epistola.catalog.file-roots[0]=\${java.io.tmpdir}"])
class DemoCatalogUpgradeReleaseIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var assembler: ReleaseContentAssembler

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var nextVersionChanges: NextVersionChanges

    private val demo = CatalogKey.of("epistola-demo")

    @Test
    fun `an installed demo catalog's release keeps what its archive holds`() {
        val tenant = createTenant("Demo installed release").id
        withMediator {
            RegisterCatalog(tenantKey = tenant, sourceUrl = DEMO_CATALOG, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant, catalogKey = demo).execute()
        }

        assertKeepsItsArchive(tenant)
    }

    @Test
    fun `a catalog installed from its source has no changes of its own, so it upgrades to the next release`(@TempDir source: Path) {
        val tenant = createTenant("Demo installed then upgraded").id
        val manifest = copyOfDemoCatalog(source)
        withMediator {
            RegisterCatalog(tenantKey = tenant, sourceUrl = manifest.toUri().toString(), authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant, catalogKey = demo).execute()
        }

        assertThat(withMediator { nextVersionChanges.changed(tenant, demo) })
            .`as`("its default theme and presets are the release's, not changes of the next version")
            .isEmpty()
        val next = nextReleaseIn(manifest)
        val upgraded = withMediator { UpgradeCatalog(tenantKey = tenant, catalogKey = demo).execute() }
        assertThat(upgraded.newVersion).isEqualTo(next)
    }

    /**
     * Its source publishes a next release: the same content under the next number, which the content
     * hash does not cover, so the fingerprint stays the archive's. @return that number.
     */
    private fun nextReleaseIn(manifest: Path): String {
        val bumped = objectMapper.readTree(Files.readAllBytes(manifest)) as ObjectNode
        val release = bumped.get("release") as ObjectNode
        val next = SemVer.parse(release.get("version").asString()).bumpPatch().toString()
        release.put("version", next)
        Files.write(manifest, objectMapper.writeValueAsBytes(bumped))
        return next
    }

    /** The bundled demo catalog copied to [directory], as a source its release can be moved on in. */
    private fun copyOfDemoCatalog(directory: Path): Path {
        val bundled = Path.of(requireNotNull(javaClass.classLoader.getResource("epistola/catalogs/demo/catalog.json")).toURI()).parent
        Files.walk(bundled).use { paths ->
            paths.forEach { path ->
                val target = directory.resolve(bundled.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return directory.resolve("catalog.json")
    }

    private fun assertKeepsItsArchive(tenant: TenantKey, number: String? = null) {
        val (version, contentHash) = jdbi.withHandle<Pair<String, String>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT r.version, r.content_hash FROM catalog_releases r JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
                WHERE r.tenant_key = :t AND c.catalog_key = :c AND (CAST(:v AS TEXT) IS NULL OR r.version = :v)
                """,
            ).bind("t", tenant).bind("c", demo).bind("v", number).map { rs, _ -> rs.getString(1) to rs.getString(2) }.one()
        }
        val retained = requireNotNull(assembler.assemble(tenant, demo, version)).content

        assertThat(CatalogCanonicalizer(objectMapper).contentHash(retained))
            .`as`("what release $version keeps hashes to the content hash it is named by")
            .isEqualTo(contentHash)
        assertThatCode { withMediator { ExportCatalogZip(tenantKey = tenant, catalogKey = demo, version = version).execute() } }
            .`as`("and it is handed on")
            .doesNotThrowAnyException()
    }

    private companion object {
        const val DEMO_CATALOG = "classpath:epistola/catalogs/demo/catalog.json"
    }
}
