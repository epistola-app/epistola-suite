// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The content hash of every release of a tenant, by catalog and number, in one read (ADR 0034): what
 * the REST and MCP lists name a catalog's highest release and a dependency's pinned release by.
 */
class ListReleaseContentHashesTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")

    private fun released(tenant: TenantKey, key: CatalogKey, vararg versions: String) = withMediator {
        CreateCatalog(tenantKey = tenant, id = key, name = key.value).execute()
        versions.forEachIndexed { index, version ->
            CreateTheme(ThemeId(ThemeKey.of("theme-$index"), testCatalog(TenantId(tenant), key)), "Theme $index").execute()
            MarkCatalogReady(testCatalog(tenant, key)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, key), version = version).execute()
        }
    }

    /** The releases in it but the bundled `system` catalog's, which every tenant has. */
    private fun ReleaseContentHashes.authored() = byRelease.keys.filterNot { it.first.key == SYSTEM_CATALOG_KEY }.map { it.first.key to it.second }

    private fun recorded(tenant: TenantKey, key: CatalogKey): Map<String?, String?> = withMediator {
        ListCatalogReleases(testCatalog(tenant, key), CatalogReleaseStatus.RELEASED).query().associate { it.version to it.contentHash }
    }

    @Test
    fun `every release of the tenant with a content hash, by catalog and number, and no other tenant's`() {
        val tenant = createTenant("Content hashes").id
        val elsewhere = createTenant("Content hashes elsewhere").id
        released(tenant, letters, "1.0.0", "1.1.0")
        released(tenant, CatalogKey.of("forms"), "2.0.0")
        released(elsewhere, letters, "9.0.0")
        val ownHashes = recorded(tenant, letters)

        val hashes = withMediator { ListReleaseContentHashes(tenant).query() }

        assertThat(hashes.authored()).containsExactlyInAnyOrder(letters to "1.0.0", letters to "1.1.0", CatalogKey.of("forms") to "2.0.0")
        assertThat(hashes.of(testCatalog(tenant, letters), "1.1.0")).startsWith("v6:").isEqualTo(ownHashes["1.1.0"])
        assertThat(hashes.of(testCatalog(tenant, letters), "9.0.0")).`as`("another tenant's").isNull()
        assertThat(hashes.of(testCatalog(tenant, letters), null)).`as`("no release").isNull()
    }

    @Test
    fun `a pin names its own content hash, else the pinned release's here, and none when it pins nothing`() {
        val tenant = createTenant("Content hashes pins").id
        released(tenant, letters, "1.0.0")
        val hash = recorded(tenant, letters)["1.0.0"]!!
        val hashes = withMediator { ListReleaseContentHashes(tenant).query() }
        val pin = CatalogDependencyDeclaration("registry.example", app.epistola.suite.testing.TestNamespaces.of(tenant).namespace, "letters", releaseVersion = "1.0.0")

        assertThat(hashes.pinnedBy(pin.copy(releaseFingerprint = "v6:${"a".repeat(64)}"))).`as`("the pin's own").isEqualTo("v6:${"a".repeat(64)}")
        assertThat(hashes.pinnedBy(pin.copy(releaseFingerprint = "v5:${"b".repeat(64)}"))).`as`("a V5 pin: the release's here").isEqualTo(hash)
        assertThat(hashes.pinnedBy(pin)).isEqualTo(hash)
        assertThat(hashes.pinnedBy(pin.copy(releaseVersion = null))).`as`("pins nothing").isNull()
    }
}
