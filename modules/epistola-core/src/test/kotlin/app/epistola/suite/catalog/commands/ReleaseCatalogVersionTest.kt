package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogReleaseStatus
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReleaseCatalogVersionTest : IntegrationTestBase() {

    @Test
    fun `release records a boundary and advances the catalog pointer`() {
        val tenant = createTenant("Release Test")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("release-cat")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Release Cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("th1"), catalogId), name = "T1").execute()

            val result = ReleaseCatalogVersion(
                tenantKey = tenantKey,
                catalogKey = catalogKey,
                version = "1.0.0",
                notes = "first",
            ).execute()

            assertThat(result.version).isEqualTo("1.0.0")
            assertThat(result.previousVersion).isNull()
            assertThat(result.fingerprint).hasSize(64)

            val catalog = GetCatalog(tenantKey, catalogKey).query()!!
            assertThat(catalog.releasedVersion).isEqualTo("1.0.0")
            assertThat(catalog.releasedFingerprint).isEqualTo(result.fingerprint)

            val status = GetCatalogReleaseStatus(tenantKey, catalogKey).query()
            assertThat(status.latestVersion).isEqualTo("1.0.0")
            assertThat(status.hasUnreleasedChanges).isFalse()
            assertThat(status.history).hasSize(1)
            assertThat(status.suggestedNext.patch).isEqualTo("1.0.1")
            assertThat(status.suggestedNext.minor).isEqualTo("1.1.0")
            assertThat(status.suggestedNext.major).isEqualTo("2.0.0")
        }
    }

    @Test
    fun `non-monotonic version is rejected`() {
        val tenant = createTenant("Monotonic Test")
        val tenantKey = tenant.id
        val catalogKey = CatalogKey.of("mono-cat")

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Mono Cat").execute()
            ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = catalogKey, version = "2.0.0").execute()

            assertThatThrownBy {
                ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = catalogKey, version = "1.9.0").execute()
            }.isInstanceOf(CatalogReleaseVersionException::class.java)

            assertThatThrownBy {
                ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = catalogKey, version = "2.0.0").execute()
            }.isInstanceOf(CatalogReleaseVersionException::class.java)

            assertThatThrownBy {
                ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = catalogKey, version = "not-semver").execute()
            }.isInstanceOf(CatalogReleaseVersionException::class.java)
        }
    }

    @Test
    fun `editing a resource after release surfaces unreleased changes`() {
        val tenant = createTenant("Drift Test")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("drift-cat")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Drift Cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("base"), catalogId), name = "Base").execute()
            ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = catalogKey, version = "1.0.0").execute()

            assertThat(GetCatalogReleaseStatus(tenantKey, catalogKey).query().hasUnreleasedChanges).isFalse()

            CreateTheme(id = ThemeId(ThemeKey.of("extra"), catalogId), name = "Extra").execute()

            assertThat(GetCatalogReleaseStatus(tenantKey, catalogKey).query().hasUnreleasedChanges).isTrue()
        }
    }
}
