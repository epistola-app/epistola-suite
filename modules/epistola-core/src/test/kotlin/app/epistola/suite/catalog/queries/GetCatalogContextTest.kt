// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

/**
 * The query behind the catalog bar, tested where it lives rather than only through the HTML that
 * renders it.
 *
 * Four answers are its own and nothing else's: the feature gate, what a release pointer plus working
 * copy drift say about whether there is anything to release, the wording of the one-line summary
 * every screen shares, and the difference between a catalog nobody authors and one that does not
 * exist. A page test can only see the last of those as "no bar", which is the same thing it sees
 * when any of them is wrong.
 */
class GetCatalogContextTest : IntegrationTestBase() {

    /**
     * The gate is the whole point of the query returning null: it is checked before the read, so
     * every screen that renders from a context is off together, and none of them needs a check.
     */
    @Test
    fun `returns nothing while the feature is off, and the context once it is on`() {
        val tenant = createTenant("Context Gate")
        val key = CatalogKey.of("gate-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Gate cat").execute()

            assertThat(context(tenant.id, key))
                .`as`("catalog-context is alpha and off by default")
                .isNull()

            enable(tenant.id)

            assertThat(context(tenant.id, key)?.name).isEqualTo("Gate cat")
        }
    }

    @Test
    fun `a catalog that was never released has everything to release and no version`() {
        val tenant = createTenant("Context Fresh")
        val key = CatalogKey.of("fresh-cat")
        withMediator {
            enable(tenant.id)
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Fresh cat").execute()

            val context = context(tenant.id, key)!!

            assertThat(context.type).isEqualTo(CatalogType.AUTHORED)
            assertThat(context.authored).isTrue()
            assertThat(context.releasedVersion).isNull()
            assertThat(context.pendingChanges).isFalse()
            // No release at all is something to release, which drift alone would not say.
            assertThat(context.releasable).isTrue()
            assertThat(context.summary).isEqualTo("Authored catalog · never released")
        }
    }

    @Test
    fun `a released catalog nobody has touched since has nothing to release`() {
        val tenant = createTenant("Context Clean")
        val key = CatalogKey.of("clean-cat")
        withMediator {
            enable(tenant.id)
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Clean cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant.id))), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()

            val context = context(tenant.id, key)!!

            assertThat(context.releasedVersion).isEqualTo("1.0.0")
            assertThat(context.pendingChanges).isFalse()
            assertThat(context.releasable).isFalse()
            assertThat(context.summary).isEqualTo("Authored catalog · last released v1.0.0")
        }
    }

    @Test
    fun `an edit after the release is pending, and makes a release available again`() {
        val tenant = createTenant("Context Drifted")
        val key = CatalogKey.of("drifted-cat")
        val catalogId = CatalogId(key, TenantId(tenant.id))
        withMediator {
            enable(tenant.id)
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Drifted cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalogId), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "2.1.0").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("after"), catalogId), name = "After").execute()

            val context = context(tenant.id, key)!!

            assertThat(context.pendingChanges).isTrue()
            assertThat(context.releasable).isTrue()
            // The version is what was last released, never what is being edited: the working copy
            // has no version until it is released, and saying otherwise is the conflation the
            // release model exists to remove.
            assertThat(context.releasedVersion).isEqualTo("2.1.0")
            assertThat(context.summary)
                .`as`("drift is carried by pendingChanges, not repeated in the summary")
                .isEqualTo("Authored catalog · last released v2.1.0")
        }
    }

    @Test
    fun `a subscribed catalog is read-only, never pending and never releasable`() {
        val tenant = createTenant("Context Subscribed")
        val key = CatalogKey.of("epistola-demo")
        withMediator {
            enable(tenant.id)
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = key).execute()

            val context = context(tenant.id, key)!!

            assertThat(context.authored).isFalse()
            assertThat(context.pendingChanges)
                .`as`("drift is a question about a working copy, which a subscribed catalog has none of")
                .isFalse()
            assertThat(context.releasable)
                .`as`("a release can only be cut from a catalog this installation authors")
                .isFalse()
            assertThat(context.summary).startsWith("Subscribed catalog, read-only · ")
        }
    }

    /**
     * A key that names nothing is null too, and must be: the bar renders from a context, so a typo
     * in a URL has to leave the page without one rather than reporting some other catalog's state.
     */
    @Test
    fun `an unknown catalog is nothing, with the feature on`() {
        val tenant = createTenant("Context Unknown")
        withMediator {
            enable(tenant.id)

            assertThat(context(tenant.id, CatalogKey.of("no-such-cat"))).isNull()
        }
    }

    /** Requires a bound mediator context, like the query it wraps. */
    private fun context(tenantKey: TenantKey, catalogKey: CatalogKey) = GetCatalogContext(tenantKey, catalogKey).query()

    private fun enable(tenantKey: TenantKey) = SaveFeatureToggle(tenantKey, KnownFeatures.CATALOG_CONTEXT, enabled = true).execute()
}
