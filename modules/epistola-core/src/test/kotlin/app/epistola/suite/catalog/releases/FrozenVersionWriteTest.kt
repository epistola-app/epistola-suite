// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.isNext
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.ThemeStore
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * ADR 0036, step (f): the freeze. A released version's rows do not change. A change made through the
 * id a draft had before it was released is refused as such (409, open the next draft), and a writer
 * of draft content refuses a released version even when something targets one.
 */
class FrozenVersionWriteTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")

    private fun brand(tenant: TenantKey) = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), letters))

    private fun nextKeyAndId(tenant: TenantKey): Pair<String, UUID> = jdbi.withHandle<Pair<String, UUID>, Exception> {
        it.createQuery(
            """
            SELECT v.version_key, v.version_id FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id
            WHERE v.tenant_key = :t AND c.catalog_key = :c AND ${isNext("v")}
            """,
        ).bind("t", tenant).bind("c", letters).map { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }.one()
    }

    private fun released(name: String): Pair<TenantKey, Pair<String, UUID>> = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(brand(tenant), "Brand").execute()
        val before = nextKeyAndId(tenant)
        mediator.releaseNext(testCatalog(tenant, letters))
        tenant to before
    }

    @Test
    fun `a change through the id of a draft that was released is refused, with where to go on`() {
        val (tenant, before) = released("frozen-by-id")

        withMediator {
            assertThatThrownBy {
                UpdateTheme(
                    brand(tenant),
                    name = "Changed after release",
                    catalogVersion = CatalogReleaseRef(testCatalog(tenant, letters), DraftRef.Id(before.first)),
                ).execute()
            }.isInstanceOf(CatalogReleaseIsReleasedException::class.java)
                .hasMessageContaining("has been released as")
                .hasMessageContaining("open the next draft")
        }
    }

    @Test
    fun `a writer of draft content refuses a released version even when a transaction targets it`() {
        val (tenant, before) = released("frozen-writer")

        assertThatThrownBy {
            jdbi.useTransaction<Exception> { handle ->
                // Target the released row the way CatalogReleaseTargeting targets a draft.
                handle.createQuery("SELECT set_config('epistola.content_version', :v, true)").bind("v", before.second.toString()).mapTo(String::class.java).one()
                DraftResources.delete(handle, testCatalog(tenant, letters), ThemeStore.THEME, "brand")
            }
        }.isInstanceOf(CatalogReleaseIsReleasedException::class.java)

        val held = jdbi.withHandle<Int, Exception> {
            it.createQuery("SELECT count(*) FROM release_resources WHERE tenant_key = :t AND version_id = :v AND resource_key = 'brand'")
                .bind("t", tenant).bind("v", before.second).mapTo(Int::class.java).one()
        }
        assertThat(held).`as`("the release still holds its theme").isEqualTo(1)
    }
}
