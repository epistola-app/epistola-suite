// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.dependencies.CatalogDependencyBackfillRunner
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A catalog from before 2.0 references other catalogs without declaring them (1.x had no
 * declarations), so it could not be released after the upgrade. The upgrade queues it and
 * [CatalogDependencyBackfillRunner] declares what it references, once.
 */
class CatalogDependencyBackfillTest : IntegrationTestBase() {

    @Autowired
    private lateinit var backfill: CatalogDependencyBackfillRunner

    @Autowired
    private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")

    /** An authored catalog whose theme uses the system catalog's Inter, declaring nothing: a 1.x catalog. */
    private fun upgradedCatalog(name: String, queued: Boolean = true): CatalogId {
        val catalog = withMediator {
            val tenant = createTenant(name).id
            CreateCatalog(tenant, letters, "Letters").execute()
            val theme = ThemeId(ThemeKey.of("brand"), CatalogId(letters, TenantId(tenant)))
            CreateTheme(theme, "Brand").execute()
            UpdateTheme(theme, documentStyles = mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to SYSTEM_CATALOG_KEY.value))).execute()
            CatalogId(letters, TenantId(tenant))
        }
        // What the upgrade migration queued for every authored catalog then; nothing else queues one.
        if (queued) {
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate("INSERT INTO catalog_dependency_backfill (tenant_key, catalog_id) VALUES (:t, ${catalogAt("t", "c")})")
                    .bind("t", catalog.tenantKey)
                    .bind("c", catalog.key)
                    .execute()
            }
        }
        return catalog
    }

    /** As the cluster scheduler runs an install-wide task: a mediator, and no principal. */
    private fun runAsScheduler() = MediatorContext.runWithMediator(mediator) { backfill.run() }

    private fun declared(catalog: CatalogId) = withMediator { ListCatalogDependencies(catalog.tenantKey, catalog.key).query() }

    @Test
    fun `a queued catalog that references system without declaring it is declared, and then releases`() {
        val catalog = upgradedCatalog("dep-backfill")
        assertThatThrownBy { withMediator { mediator.releaseNext(catalog) } }
            .hasMessageContaining("which catalogDependencies does not declare")

        runAsScheduler()

        assertThat(declared(catalog).map { it.catalogKey to it.version }).containsExactly(SYSTEM_CATALOG_KEY.value to "*")
        assertThat(declared(catalog).single().releaseVersion).`as`("locked to the installed system release").isNotNull()
        withMediator { mediator.releaseNext(catalog) }
    }

    @Test
    fun `a catalog leaves the queue once declared, so a second run changes nothing`() {
        val catalog = upgradedCatalog("dep-backfill-twice")

        runAsScheduler()
        withMediator { mediator.releaseNext(catalog) }
        runAsScheduler()

        assertThat(declared(catalog)).hasSize(1)
        val stillQueued = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT count(*) FROM catalog_dependency_backfill WHERE tenant_key = :t")
                .bind("t", catalog.tenantKey)
                .mapTo(Int::class.java)
                .one()
        }
        assertThat(stillQueued).isZero()
    }

    @Test
    fun `a catalog created under 2_0 is not queued, so what its author leaves undeclared stays undeclared`() {
        val catalog = upgradedCatalog("dep-backfill-new", queued = false)

        runAsScheduler()

        assertThat(declared(catalog)).isEmpty()
    }
}
