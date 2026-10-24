// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.ThemeStorageConversion
import app.epistola.suite.testing.default
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.ListThemes
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * A draft's themes are stored as JSON (ADR 0033, step 1b): a `release_resources` row of the draft
 * naming its content in `resource_contents`, the theme's wire form. The `themes` table is gone.
 */
class ThemeStorageTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var themes: ThemeStore

    private data class Row(val resourceId: UUID, val contentHash: String, val revision: Long, val payload: String)

    private fun row(tenant: TenantId, key: String): Row? = jdbi.withHandle<Row?, Exception> { h ->
        h.createQuery(
            """
            SELECT r.resource_id, r.content_hash, r.revision, c.payload::text AS payload FROM release_resources r
            JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
            WHERE r.tenant_key = :t AND r.version_id IS NOT NULL AND r.resource_type = 'theme' AND r.resource_key = :k
            """,
        ).bind("t", tenant.key).bind("k", key)
            .map { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getLong(3), rs.getString(4)) }
            .findOne().orElse(null)
    }

    private fun identityExists(tenant: TenantId, resourceId: UUID): Boolean = jdbi.withHandle<Boolean, Exception> { h ->
        h.createQuery("SELECT count(*) > 0 FROM catalog_resources WHERE tenant_key = :t AND resource_id = :r")
            .bind("t", tenant.key).bind("r", resourceId).mapTo(Boolean::class.java).one()
    }

    @Test
    fun `a theme is a draft row naming its JSON content, and the themes table is gone`() {
        val tenant = TenantId(createTenant("Theme storage").id)
        val brand = ThemeId(ThemeKey.of("brand"), CatalogId.default(tenant))

        val created = withMediator { CreateTheme(brand, "Brand", description = "House style", spacingUnit = 4f).execute() }

        val stored = requireNotNull(row(tenant, "brand")) { "the draft holds a row of the theme" }
        val sameContent = jdbi.withHandle<Boolean, Exception> { h ->
            h.createQuery("SELECT CAST(:stored AS JSONB) = CAST(:expected AS JSONB)")
                .bind("stored", stored.payload).bind("expected", objectMapper.writeValueAsString(themes.payload("brand", created.fields())))
                .mapTo(Boolean::class.java).one()
        }
        assertThat(sameContent).`as`("the theme's wire form: %s", stored.payload).isTrue()
        assertThat(stored.revision).isEqualTo(1)
        assertThat(jdbi.withHandle<Boolean, Exception> { it.createQuery("SELECT to_regclass('themes') IS NULL").mapTo(Boolean::class.java).one() }).isTrue()
    }

    @Test
    fun `an update stores new content and raises the revision only when the theme changed`() {
        val tenant = TenantId(createTenant("Theme storage update").id)
        val brand = ThemeId(ThemeKey.of("brand"), CatalogId.default(tenant))
        withMediator { CreateTheme(brand, "Brand").execute() }
        val before = requireNotNull(row(tenant, "brand"))

        withMediator { UpdateTheme(brand, name = "Brand").execute() }
        assertThat(row(tenant, "brand")).`as`("the same content").isEqualTo(before)

        val renamed = withMediator { UpdateTheme(brand, name = "Brand, renamed", spacingUnit = 1.333f).execute() }
        val after = requireNotNull(row(tenant, "brand"))
        assertThat(after.contentHash).isNotEqualTo(before.contentHash)
        assertThat(after.revision).isEqualTo(2)
        assertThat(after.resourceId).isEqualTo(before.resourceId)
        assertThat(renamed?.spacingUnit).`as`("two decimals, as the column kept it").isEqualTo(1.33f)
    }

    @Test
    fun `a deleted theme takes its row and, held nowhere else, its identity`() {
        val tenant = TenantId(createTenant("Theme storage delete").id)
        val brand = ThemeId(ThemeKey.of("brand"), CatalogId.default(tenant))
        withMediator { CreateTheme(brand, "Brand").execute() }
        val identity = requireNotNull(row(tenant, "brand")).resourceId

        assertThat(withMediator { DeleteTheme(brand).execute() }).isTrue()

        assertThat(row(tenant, "brand")).isNull()
        assertThat(identityExists(tenant, identity)).isFalse()
    }

    @Test
    fun `the migration converts every bundled theme as the suite stores it, both ways`() {
        val tenant = createTenant("Theme storage conversion").id
        val catalog = systemCatalog(tenant)
        val bundled = withMediator { ListThemes(catalog.tenantId, catalog = catalog).query() }
        assertThat(bundled).isNotEmpty()
        ThemeStorageConversion.verify(jdbi, objectMapper, catalog, bundled)
    }

    @Test
    fun `the migration converts a theme with every field set as the suite stores it`() {
        val tenant = TenantId(createTenant("Theme storage conversion, every field").id)
        val brand = ThemeId(ThemeKey.of("brand"), CatalogId.default(tenant))
        val theme = withMediator {
            CreateTheme(
                brand,
                "Brand",
                description = "Every field",
                documentStyles = mapOf("fontSize" to "11pt", "color" to "#112233", "fontFamily" to mapOf("key" to "inter")),
                pageSettings = app.epistola.template.model.PageSettings(format = app.epistola.template.model.PageFormat.A4, orientation = app.epistola.template.model.Orientation.portrait, margins = app.epistola.template.model.Margins(top = 10, right = 12, bottom = 10, left = 12)),
                blockStylePresets = BlockStylePresets.of("callout" to app.epistola.template.model.BlockStylePreset(label = "Callout", styles = mapOf("padding" to "4sp"))),
                spacingUnit = 4.5f,
            ).execute()
        }
        ThemeStorageConversion.verify(jdbi, objectMapper, brand.catalogId, listOf(theme))
    }
}
