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
import org.junit.jupiter.api.Test

/**
 * [GetLatestCatalogRelease] is the cheap pointer/history read (no
 * `CatalogContentBuilder`/fingerprint dependency — its handler only takes
 * `Jdbi`), split from the drift-aware [GetCatalogReleaseStatus] so exports
 * don't double-build catalog content.
 */
class GetLatestCatalogReleaseTest : IntegrationTestBase() {

    @Test
    fun `never released catalog has no latest and distinct zero-based bumps`() {
        val tenant = createTenant("Latest None")
        val catalogKey = CatalogKey.of("latest-none")

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Latest None").execute()

            val r = GetLatestCatalogRelease(tenant.id, catalogKey).query()
            assertThat(r.latestVersion).isNull()
            assertThat(r.latestFingerprint).isNull()
            assertThat(r.history).isEmpty()
            assertThat(r.suggestedNext.patch).isEqualTo("0.0.1")
            assertThat(r.suggestedNext.minor).isEqualTo("0.1.0")
            assertThat(r.suggestedNext.major).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `returns highest release, history and next bumps`() {
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
            assertThat(r.history.map { it.version }).containsExactlyInAnyOrder("1.0.0", "1.1.0")
            assertThat(r.suggestedNext.patch).isEqualTo("1.1.1")
            assertThat(r.suggestedNext.minor).isEqualTo("1.2.0")
            assertThat(r.suggestedNext.major).isEqualTo("2.0.0")
        }
    }
}
