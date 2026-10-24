// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalogReleaseDetail
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestPrincipalUser
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * A release records who released it: the user and this installation for one cut here, the
 * installation that cut it for one received, which travels in `release.metadata`
 * (`app.epistola.suite.releasedByInstallation`).
 */
class ReleasedByTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var installation: InstallationService

    private val archives = ReleaseDescriptionTest()

    private fun manifestOf(bytes: ByteArray): CatalogManifest = objectMapper.readValue(archives.entries(bytes)["catalog.json"]!!, CatalogManifest::class.java)

    private fun releasedBy(tenant: app.epistola.suite.common.ids.TenantKey, catalog: CatalogKey, version: String): Pair<UUID?, UUID?> = jdbi.withHandle<Pair<UUID?, UUID?>, Exception> { h ->
        h.createQuery("SELECT released_by, released_by_installation FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v")
            .bind("t", tenant).bindCatalog("c", testCatalog(tenant, catalog)).bind("v", version)
            .map { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
            .one()
    }

    @Test
    fun `a release cut here records its user and this installation, and its archive names the installation`() {
        val a = createTenant("Released By Here").id
        val catalog = CatalogKey.of("released-by-here")
        val here = installation.get().id
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = "Released By").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(a), catalog)), name = "Brand").execute()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.0.0").execute()

            assertThat(releasedBy(a, catalog, "1.0.0")).isEqualTo(TestPrincipalUser.ID.value to here)
            val detail = GetCatalogReleaseDetail(testCatalog(a, catalog), "1.0.0").query()!!
            assertThat(detail.releasedHere).isTrue()
            assertThat(detail.releasedByLine).isEqualTo("Released here by ${TestPrincipalUser.DISPLAY_NAME}")

            val metadata = manifestOf(ExportCatalogZip(testCatalog(a, catalog), "1.0.0").execute().zipBytes).release.metadata
            assertThat(metadata?.get(ReleaseDescription.NAMESPACE)?.get(ReleaseDescription.RELEASED_BY_INSTALLATION)).isEqualTo(here.toString())
        }
    }

    @Test
    fun `a received release records the installation that cut it, nobody here, and keeps it on the way on`() {
        val a = createTenant("Released By Producer").id
        val b = createTenant("Released By Receiver").id
        val c = createTenant("Released By Third").id
        val catalog = CatalogKey.of("released-by-received")
        val elsewhere = UUID.fromString("7d0c5a1e-3f2b-4c8d-9e6f-0a1b2c3d4e5f")
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = "Released By").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(a), catalog)), name = "Brand").execute()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.0.0").execute()
            // As another installation would have released it.
            val all = archives.entries(ExportCatalogZip(testCatalog(a, catalog), "1.0.0").execute().zipBytes)
            val manifest = objectMapper.readTree(all["catalog.json"]!!) as ObjectNode
            ((manifest.get("release").get("metadata") as ObjectNode).get(ReleaseDescription.NAMESPACE) as ObjectNode)
                .put(ReleaseDescription.RELEASED_BY_INSTALLATION, elsewhere.toString())
            all["catalog.json"] = objectMapper.writeValueAsBytes(manifest)

            assertThat(ImportCatalogZip(tenantKey = b, zipBytes = archives.zip(all)).execute().aborted).isFalse()

            assertThat(releasedBy(b, catalog, "1.0.0")).isEqualTo(null to elsewhere)
            val detail = GetCatalogReleaseDetail(testCatalog(b, catalog), "1.0.0").query()!!
            assertThat(detail.releasedHere).isFalse()
            assertThat(detail.releasedByLine).isEqualTo("Released by installation $elsewhere")

            // Passed on, it still names the installation that cut it, not this one.
            val passedOn = ExportCatalogZip(testCatalog(b, catalog), "1.0.0").execute().zipBytes
            assertThat(manifestOf(passedOn).release.metadata?.get(ReleaseDescription.NAMESPACE)?.get(ReleaseDescription.RELEASED_BY_INSTALLATION)).isEqualTo(elsewhere.toString())
            ImportCatalogZip(tenantKey = c, zipBytes = passedOn).execute()
            assertThat(releasedBy(c, catalog, "1.0.0")).isEqualTo(null to elsewhere)
        }
    }
}
