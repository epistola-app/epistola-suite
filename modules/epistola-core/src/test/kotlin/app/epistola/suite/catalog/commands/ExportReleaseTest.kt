// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
            MarkCatalogReady(catalog.tenantKey, catalog.key).execute()
            ReleaseCatalogDraft(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }

        withMediator {
            UpdateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Edited since").execute()
        }

        val release = withMediator {
            ExportCatalogZip(catalog.tenantKey, catalog.key, version = "1.0.0").execute()
        }
        val highest = withMediator { ExportCatalogZip(catalog.tenantKey, catalog.key).execute() }

        assertThat(release.filename).isEqualTo("exp-release-1.0.0.zip")
        val manifest = entryOf(release.zipBytes, "catalog.json")
        assertThat(manifest)
            .`as`("the archive advertises the version and fingerprint it was released with")
            .contains("\"version\":\"1.0.0\"", released.fingerprint)
        assertThat(entryOf(release.zipBytes, "resources/theme/brand.json")).contains("As released")

        // Naming no version exports the highest release, never the draft's edit (contract 2.0).
        assertThat(highest.filename).isEqualTo("exp-release-1.0.0.zip")
        assertThat(entryOf(highest.zipBytes, "resources/theme/brand.json")).contains("As released").doesNotContain("Edited since")
    }

    @Test
    fun `an unknown version refuses`() {
        val catalog = authoredCatalog("exp-unknown")

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
            MarkCatalogReady(catalog.tenantKey, catalog.key).execute()
            ReleaseCatalogDraft(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }

        assertThatThrownBy {
            withMediator { ExportCatalogZip(catalog.tenantKey, catalog.key, version = "9.9.9").execute() }
        }.isInstanceOf(CatalogReleaseNotFoundException::class.java)
    }

    @Test
    fun `a subscribed catalog exports the release it installed, as its publisher released it`() {
        val publisher = authoredCatalog("exp-subscribed")
        val archive = withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), publisher), name = "Brand").execute()
            MarkCatalogReady(publisher.tenantKey, publisher.key).execute()
            ReleaseCatalogDraft(tenantKey = publisher.tenantKey, catalogKey = publisher.key, version = "1.2.0").execute()
            ExportCatalogZip(publisher.tenantKey, publisher.key).execute()
        }
        val subscriber = createTenant("exp-subscriber").id
        withMediator { ImportCatalogZip(subscriber, archive.zipBytes).execute() }

        val export = withMediator { ExportCatalogZip(subscriber, publisher.key).execute() }

        assertThat(export.filename).isEqualTo("exp-subscribed-1.2.0.zip")
        assertThat(entryOf(export.zipBytes, "catalog.json")).contains("\"version\":\"1.2.0\"", archiveFingerprint(archive.zipBytes))
    }

    private fun archiveFingerprint(zip: ByteArray): String = Regex("\"fingerprint\":\"(v6:[0-9a-f]+)\"").find(entryOf(zip, "catalog.json"))!!.groupValues[1]

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
}
