// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListVisibleCatalogs
import app.epistola.suite.catalog.queries.VisibleCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ContentVersion
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Every catalog can use the system catalog from the start: a new catalog declares it at its highest
 * release, the migration declares it on drafts from before, and what a version can use is its own
 * catalog and the catalogs it declares, each at the version its declaration names.
 */
class SystemCatalogDeclarationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var store: CatalogDependencyStore

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")
    private val other = CatalogKey.of("other")

    private fun systemRelease(tenant: TenantKey): String = withMediator { GetCatalog(systemCatalog(tenant)).query()!!.latestReleaseVersion!! }

    private fun declarations(tenant: TenantKey, key: CatalogKey) = withMediator { ListCatalogDependencies(testCatalog(tenant, key), catalogVersion = null).query() }

    private fun systemDeclarations(tenant: TenantKey, key: CatalogKey) = declarations(tenant, key).filter { it.namespace == "epistola" && it.catalogKey == SYSTEM_CATALOG_KEY.value }

    @Test
    fun `a new catalog declares the system catalog at its highest release`() {
        val tenant = createTenant("system-declared").id
        withMediator { CreateCatalog(tenant, letters, "Letters").execute() }

        val declared = systemDeclarations(tenant, letters).single()

        assertThat(declared.releaseVersion).isEqualTo(systemRelease(tenant))
        assertThat(declared.releaseFingerprint).isNotNull()
    }

    @Test
    fun `a version sees its own catalog first, then each catalog it declares at the version the declaration names`() {
        val tenant = createTenant("visible-catalogs").id
        val (draftKey, visible) = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, shared, "Shared").execute()
            CreateCatalog(tenant, other, "Other").execute()
            MarkCatalogReady(testCatalog(tenant, shared), catalogVersion = null).execute()
            ReleaseCatalogDraft(testCatalog(tenant, shared), "1.0.0", catalogVersion = null).execute()
            val draft = CreateCatalogDraft(testCatalog(tenant, other), "Redesign").execute().versionId
            val sharedCatalog = GetCatalog(testCatalog(tenant, shared)).query()!!
            val otherCatalog = GetCatalog(testCatalog(tenant, other)).query()!!
            val system = systemDeclarations(tenant, letters).single()
            UpdateCatalogDependencies(
                testCatalog(tenant, letters),
                listOf(
                    DeclaredCatalogDependency(system.registry, system.namespace, system.catalogKey, system.releaseVersion),
                    DeclaredCatalogDependency(sharedCatalog.registry, sharedCatalog.namespace, shared.value, releaseVersion = "1.0.0"),
                    DeclaredCatalogDependency(otherCatalog.registry, otherCatalog.namespace, other.value, versionId = draft),
                ),
                catalogVersion = null,
            ).execute()
            draft to ListVisibleCatalogs(testCatalog(tenant, letters), catalogVersion = null).query().catalogs
        }

        assertThat(visible).containsExactly(
            VisibleCatalog(testCatalog(tenant, letters), null),
            VisibleCatalog(systemCatalog(tenant), CatalogReleaseRef(systemCatalog(tenant), DraftRef.Released(systemRelease(tenant)))),
            VisibleCatalog(testCatalog(tenant, shared), CatalogReleaseRef(testCatalog(tenant, shared), DraftRef.Released("1.0.0"))),
            VisibleCatalog(testCatalog(tenant, other), CatalogReleaseRef(testCatalog(tenant, other), DraftRef.Id(draftKey))),
        )
    }

    @Test
    fun `a catalog it does not declare is not visible`() {
        val tenant = createTenant("not-visible").id
        val visible = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, shared, "Shared").execute()
            ListVisibleCatalogs(testCatalog(tenant, letters), catalogVersion = null).query().catalogs.map { it.catalog }
        }

        assertThat(visible).containsExactly(testCatalog(tenant, letters), systemCatalog(tenant))
    }

    @Test
    fun `the migration declares the system catalog on drafts without it, and leaves releases as they are`() {
        val tenant = createTenant("system-migrated").id
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, shared, "Shared").execute()
            // A catalog from before: it does not declare the system catalog, and was released so.
            UpdateCatalogDependencies(testCatalog(tenant, letters), emptyList(), catalogVersion = null).execute()
            MarkCatalogReady(testCatalog(tenant, letters), catalogVersion = null).execute()
            ReleaseCatalogDraft(testCatalog(tenant, letters), "1.0.0", catalogVersion = null).execute()
        }
        assertThat(systemDeclarations(tenant, letters)).isEmpty()
        val sharedBefore = declarations(tenant, shared)

        // The statement is global, so it runs in a transaction that is rolled back, and what it did is
        // read in that transaction: the other tests' tenants in this database are left as they were.
        jdbi.useTransaction<Exception> { handle ->
            val sql = javaClass.getResource(MIGRATION)!!.readText()
            repeat(2) { handle.connection.createStatement().use { it.execute(sql) } }
            fun declared(key: CatalogKey) = store.declarations(handle, ContentVersion.next(handle, testCatalog(tenant, key)))

            val system = declared(letters).single { it.namespace == "epistola" && it.catalogKey == SYSTEM_CATALOG_KEY.value }
            val catalog = withMediator { GetCatalog(systemCatalog(tenant)).query()!! }
            assertThat(system.releaseVersion).isEqualTo(catalog.latestReleaseVersion)
            assertThat(system.releaseFingerprint).isEqualTo(catalog.latestReleaseFingerprint)
            assertThat(system.alias).isEqualTo(SYSTEM_CATALOG_KEY.value)
            assertThat(declared(shared)).`as`("it declared the system catalog already, once").isEqualTo(sharedBefore)
            assertThat(store.releasePins(handle, testCatalog(tenant, letters), "1.0.0")).`as`("a release is frozen").isEmpty()
            assertThat(store.declarations(handle, ContentVersion.next(handle, systemCatalog(tenant)))).`as`("the system catalog declares nothing").isEmpty()
            handle.rollback()
        }
    }

    private companion object {
        const val MIGRATION = "/db/migration/core/V20261110105832__core_declare_system_catalog.sql"
    }
}
