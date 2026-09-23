// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * [GetLatestCatalogRelease] is the cheap pointer read (no
 * `CatalogContentBuilder`/fingerprint dependency — its handler only takes
 * `Jdbi`), split from the drift-aware [GetCatalogReleaseStatus] so exports
 * don't double-build catalog content.
 */
class GetLatestCatalogReleaseTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `never released catalog has no latest and distinct zero-based bumps`() {
        val tenant = createTenant("Latest None")
        val catalogKey = CatalogKey.of("latest-none")

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Latest None").execute()

            val r = GetLatestCatalogRelease(tenant.id, catalogKey).query()
            assertThat(r.latestVersion).isNull()
            assertThat(r.latestFingerprint).isNull()
            assertThat(r.suggestedNext.patch).isEqualTo("0.0.1")
            assertThat(r.suggestedNext.minor).isEqualTo("0.1.0")
            assertThat(r.suggestedNext.major).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `returns highest release and next bumps`() {
        val tenant = createTenant("Latest Hist")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("latest-hist")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Latest Hist").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("th1"), catalogId), name = "T1").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "1.0.0").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("th2"), catalogId), name = "T2").execute()
            val v110 = ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "1.1.0").execute()

            val r = GetLatestCatalogRelease(tenant.id, catalogKey).query()
            assertThat(r.latestVersion).isEqualTo("1.1.0")
            assertThat(r.latestFingerprint).isEqualTo(v110.fingerprint)
            assertThat(r.suggestedNext.patch).isEqualTo("1.1.1")
            assertThat(r.suggestedNext.minor).isEqualTo("1.2.0")
            assertThat(r.suggestedNext.major).isEqualTo("2.0.0")
        }
    }

    /**
     * `1.10.0` sorts before `1.9.0` as text, so ordering has to use the version's components. The
     * generated columns (V20260923150918) are what let the query say that in SQL.
     */
    @Test
    fun `a two-digit minor is newer than a single-digit one`() {
        val tenant = createTenant("Latest Minor")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("latest-minor")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Latest Minor").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("th1"), catalogId), name = "T1").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "1.9.0").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("th2"), catalogId), name = "T2").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "1.10.0").execute()

            val r = GetLatestCatalogRelease(tenant.id, catalogKey).query()

            assertThat(r.latestVersion).isEqualTo("1.10.0")
            assertThat(r.suggestedNext.patch).isEqualTo("1.10.1")
        }

        // The columns the ordering above relies on are generated from `version`, so they are filled
        // for a row this test never wrote them to.
        assertThat(versionComponents(tenant.id.value, catalogKey.value, "1.10.0")).isEqualTo(Triple(1, 10, 0))
        assertThat(versionComponents(tenant.id.value, catalogKey.value, "1.9.0")).isEqualTo(Triple(1, 9, 0))
    }

    private fun versionComponents(tenantKey: String, catalogKey: String, version: String): Triple<Int?, Int?, Int?> = jdbi.withHandle<Triple<Int?, Int?, Int?>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT version_major, version_minor, version_patch
                FROM catalog_releases
                WHERE tenant_key = :t AND catalog_key = :c AND version = :v
                """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("v", version)
            .map { rs, _ ->
                Triple(
                    rs.getObject("version_major") as Int?,
                    rs.getObject("version_minor") as Int?,
                    rs.getObject("version_patch") as Int?,
                )
            }
            .one()
    }
}
