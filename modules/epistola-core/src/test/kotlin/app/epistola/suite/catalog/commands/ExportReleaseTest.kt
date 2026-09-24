// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.zip.ZipInputStream

/**
 * Exporting a **release** hands over what was released, not what the catalog looks like now.
 *
 * Today's export builds from the working copy and labels it `-dev` when that has drifted, which is
 * honest but not useful to anyone who wants the version they published. These are the tests for the
 * other source.
 */
class ExportReleaseTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `exporting a release gives the released content, not the working copy`() {
        val catalog = authoredCatalog("exp-release")

        val released = withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "As released").execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }

        withMediator {
            UpdateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Edited since").execute()
        }

        val release = withMediator {
            ExportCatalogZip(catalog.tenantKey, catalog.key, version = "1.0.0").execute()
        }
        val working = withMediator { ExportCatalogZip(catalog.tenantKey, catalog.key).execute() }

        assertThat(release.filename).isEqualTo("exp-release-1.0.0.zip")
        val manifest = entryOf(release.zipBytes, "catalog.json")
        assertThat(manifest)
            .`as`("the archive advertises the version and fingerprint it was released with")
            .contains("\"version\":\"1.0.0\"", released.fingerprint)
        assertThat(entryOf(release.zipBytes, "resources/theme/brand.json")).contains("As released")

        // The working copy has moved on, and says so in its own label rather than quietly
        // shipping the edit as 1.0.0.
        assertThat(working.filename).isEqualTo("exp-release-1.0.0-dev.zip")
        assertThat(entryOf(working.zipBytes, "resources/theme/brand.json")).contains("Edited since")
    }

    @Test
    fun `a release that retained no content refuses rather than exporting the working copy`() {
        val catalog = authoredCatalog("exp-legacy")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }
        forgetEntries(catalog)

        assertThatThrownBy {
            withMediator { ExportCatalogZip(catalog.tenantKey, catalog.key, version = "1.0.0").execute() }
        }
            .isInstanceOf(CatalogReleaseNotRetainedException::class.java)
            .hasMessageContaining("did not retain its content")
    }

    @Test
    fun `an unknown version refuses`() {
        val catalog = authoredCatalog("exp-unknown")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }

        assertThatThrownBy {
            withMediator { ExportCatalogZip(catalog.tenantKey, catalog.key, version = "9.9.9").execute() }
        }.isInstanceOf(CatalogReleaseNotRetainedException::class.java)
    }

    private fun authoredCatalog(slug: String): CatalogId {
        val tenant = createTenant(slug)
        val catalogKey = CatalogKey.of(slug)
        withMediator { CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = slug).execute() }
        return CatalogId(catalogKey, TenantId(tenant.id))
    }

    private fun entryOf(zip: ByteArray, path: String): String {
        ZipInputStream(zip.inputStream()).use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                if (entry.name == path) return stream.readBytes().decodeToString()
                entry = stream.nextEntry
            }
        }
        error("No $path in the archive")
    }

    /**
     * Raw SQL: makes a release look like one cut before releases retained their content. No command
     * can produce that row any more, and the refusal it exercises exists only for those releases.
     */
    private fun forgetEntries(catalog: CatalogId) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("DELETE FROM release_entries WHERE tenant_key = :t AND catalog_key = :c")
            .bind("t", catalog.tenantKey)
            .bind("c", catalog.key)
            .execute()
    }
}
