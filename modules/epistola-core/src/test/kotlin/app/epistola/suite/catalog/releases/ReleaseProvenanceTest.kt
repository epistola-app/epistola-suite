// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalogReleaseDetail
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestPrincipalUser
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.toPath

/**
 * Every release records how it came to be in its tenant (`released_via`, `released_from`), who
 * released or installed it and when, and the release page says so. Provenance only: a release is a
 * release however it arrived (ADR 0036), which `ReleaseProvenanceUsageTest` holds.
 */
class ReleaseProvenanceTest : IntegrationTestBase() {

    private fun releasesOf(catalog: CatalogId) = ListCatalogReleases(catalog, CatalogReleaseStatus.RELEASED).query()

    private fun released(tenant: TenantKey, key: CatalogKey): CatalogId = withMediator {
        CreateCatalog(tenantKey = tenant, id = key, name = "Provenance").execute()
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), key)), name = "Brand").execute()
        MarkCatalogReady(testCatalog(tenant, key)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, key), version = "1.0.0").execute()
        testCatalog(tenant, key)
    }

    @Test
    fun `a release cut here says it was released here, by whom`() {
        val catalog = released(createTenant("Provenance Here").id, CatalogKey.of("prov-here"))
        withMediator {
            val release = releasesOf(catalog).single()
            assertThat(release.releasedVia).isEqualTo(ReleaseChannel.RELEASED)
            assertThat(release.releasedFrom).isNull()
            val detail = GetCatalogReleaseDetail(catalog, "1.0.0").query()!!
            assertThat(detail.releasedByLine).isEqualTo("Released here by ${TestPrincipalUser.DISPLAY_NAME}")
            // Said once: the page's "released by" line is it.
            assertThat(detail.arrivalLine).isNull()
        }
    }

    @Test
    fun `an uploaded archive says it was uploaded, as which file and by whom`() {
        val key = CatalogKey.of("prov-upload")
        val source = released(createTenant("Provenance Upload Source").id, key)
        val receiver = createTenant("Provenance Upload").id
        withMediator {
            val zip = ExportCatalogZip(source, "1.0.0").execute().zipBytes
            ImportCatalogZip(tenantKey = receiver, zipBytes = zip, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD, "prov-upload-1.0.0.zip")).execute()

            val release = releasesOf(testCatalog(receiver, key)).single()
            assertThat(release.releasedVia).isEqualTo(ReleaseChannel.UPLOAD)
            assertThat(release.releasedFrom).isEqualTo("prov-upload-1.0.0.zip")
            assertThat(GetCatalogReleaseDetail(testCatalog(receiver, key), "1.0.0").query()!!.arrivalLine)
                .isEqualTo("Uploaded as prov-upload-1.0.0.zip by ${TestPrincipalUser.DISPLAY_NAME}")
        }
    }

    @Test
    fun `a release received from a catalog's source says where from`(@TempDir tmp: Path) {
        val tenant = createTenant("Provenance Source").id
        val url = fixture(tmp)
        val key = CatalogKey.of("dep-test")
        withMediator {
            RegisterCatalog(tenantKey = tenant, sourceUrl = url, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant, key)).execute()

            val release = releasesOf(testCatalog(tenant, key)).single()
            assertThat(release.releasedVia).isEqualTo(ReleaseChannel.SOURCE)
            assertThat(release.releasedFrom).isEqualTo(url)
            assertThat(GetCatalogReleaseDetail(testCatalog(tenant, key), release.version!!).query()!!.arrivalLine)
                .isEqualTo("Received from $url by ${TestPrincipalUser.DISPLAY_NAME}")
        }
    }

    @Test
    fun `the system catalog says it was supplied with Epistola, by nobody`() {
        val tenant = createTenant("Provenance Bundled").id
        withMediator {
            val system = testCatalog(tenant, CatalogKey.of("system")).let { CatalogId(app.epistola.suite.catalog.CatalogIdentityProperties.BUNDLED_NAMESPACE, it.key, it.tenantId) }
            val release = releasesOf(system).single()
            assertThat(release.releasedVia).isEqualTo(ReleaseChannel.BUNDLED)
            assertThat(release.releasedFrom).startsWith("classpath:")
            val detail = GetCatalogReleaseDetail(system, release.version!!).query()!!
            assertThat(detail.releasedByUser).`as`("the system is nobody to name").isNull()
            assertThat(detail.arrivalLine).isEqualTo("Supplied with Epistola")
        }
    }

    /** A copy of the dependency-test fixture at [dir]: a catalog source serving release 1.0.0. */
    private fun fixture(dir: Path): String {
        val src = javaClass.classLoader.getResource("epistola/catalogs/test/dependency-test/catalog.json")!!.toURI().toPath().parent
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = dir.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return dir.resolve("catalog.json").toUri().toString()
    }
}
