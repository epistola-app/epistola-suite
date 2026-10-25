// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.CountStencils
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.ListStencils
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.StencilStorageConversion
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * A draft's stencils are stored as JSON (ADR 0033, step 4): `{ "resource": <StencilResource> }`
 * named by a draft row. The references its document holds (a nested stencil, a theme) are its wire
 * form's alias and key, indexed in `resource_references` when stored; tags are filtered in SQL over
 * the JSON.
 */
class StencilStorageTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var stencils: StencilStore

    private fun one(sql: String): String = jdbi.withHandle<String, Exception> { it.createQuery(sql).mapTo(String::class.java).one() }

    private fun usesHeaderAndBrand(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("children")),
            "header" to Node(id = "header", type = "stencil", slots = listOf("header-slot"), props = mapOf("stencilKey" to "header", "catalogKey" to "parts")),
        ),
        slots = mapOf(
            "children" to Slot(id = "children", nodeId = "root", name = "children", children = listOf("header")),
            "header-slot" to Slot(id = "header-slot", nodeId = "header", name = "children", children = emptyList()),
        ),
        themeRef = ThemeRefOverride(themeKey = "brand"),
    )

    @Test
    fun `a stencil is content naming what it uses by alias and key, indexed, and the table is gone`() {
        val tenant = createTenant("Stencil storage").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val parts = testCatalog(tenant, CatalogKey.of("parts"))
        val footer = StencilId(StencilKey.of("footer"), letters)
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateCatalog(tenant, parts.key, "Parts").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), letters), "Brand").execute()
            CreateStencil(StencilId(StencilKey.of("header"), parts), "Header").execute()
            val created = CreateStencil(footer, "Footer", description = "At the bottom", tags = listOf("legal"), content = usesHeaderAndBrand()).execute()
            assertThat(created).`as`("what a create returns is what a read gives").isEqualTo(GetStencil(footer).query())
        }

        val payload = objectMapper.readTree(one("SELECT payload::text FROM current_stencils WHERE tenant_key = '${tenant.value}' AND id = 'footer'"))
        assertThat(payload.path("resource").path("type").asString()).isEqualTo("stencil")
        assertThat(payload.path("resource").path("tags").toList().map { it.asString() }).containsExactly("legal")
        assertThat(
            one(
                """
                SELECT string_agg(COALESCE(x.alias, '') || '/' || x.resource_type || '/' || x.resource_key, ',' ORDER BY x.resource_type DESC) FROM current_stencils s
                JOIN release_resources r ON r.tenant_key = s.tenant_key AND r.version_id = s.version_id AND r.resource_type = 'stencil' AND r.resource_key = s.id
                JOIN resource_references x ON x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash
                WHERE s.tenant_key = '${tenant.value}' AND s.id = 'footer'
                """,
            ),
        ).`as`("the nested stencil by its catalog's alias; the theme of its own catalog relative, as a release says it").isEqualTo("/theme/brand,parts/stencil/header")
        assertThat(payload.path("resource").path("content").path("themeRef").path("catalogKey").isString).`as`("stored relative").isFalse()
        assertThat(withMediator { GetStencil(footer).query() }!!.content.themeRef).`as`("read qualified")
            .isEqualTo(ThemeRefOverride(themeKey = "brand", catalogKey = "letters"))
        assertThat(one("SELECT CAST(to_regclass('stencils') IS NULL AS TEXT)")).isEqualTo("true")

        val saved = withMediator { UpdateStencil(footer, name = "Footer, renamed").execute() }
        assertThat(saved).`as`("what a save returns is what a read gives").isEqualTo(withMediator { GetStencil(footer).query() })
        assertThat(saved!!.description).`as`("a save keeps what it does not change").isEqualTo("At the bottom")
        assertThat(saved.tags).containsExactly("legal")
        assertThat(saved.content).isEqualTo(withMediator { GetStencil(footer).query() }!!.content)
    }

    @Test
    fun `tags filter in SQL over the content, in a list and a count`() {
        val tenant = createTenant("Stencil tags").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateStencil(StencilId(StencilKey.of("alpha"), letters), "A", tags = listOf("legal", "footer")).execute()
            CreateStencil(StencilId(StencilKey.of("beta"), letters), "B", tags = listOf("header")).execute()
            CreateStencil(StencilId(StencilKey.of("gamma"), letters), "C").execute()
        }
        withMediator {
            assertThat(ListStencils(TenantId(tenant), tag = "legal").query().map { it.id.value }).containsExactly("alpha")
            assertThat(ListStencils(TenantId(tenant), tag = "header").query().map { it.id.value }).containsExactly("beta")
            assertThat(CountStencils(TenantId(tenant), tag = "footer").query()).isEqualTo(1)
            assertThat(ListStencils(TenantId(tenant), tag = "lega").query()).`as`("a whole tag, not a part").isEmpty()
            assertThat(ListStencils(TenantId(tenant), tag = "say \"hi\"").query()).`as`("a quote is part of the tag").isEmpty()
            assertThat(CountStencils(TenantId(tenant), tag = "say \"hi\"").query()).isZero()
        }
    }

    @Test
    fun `a stencil reads the same from the next version and from the release it holds`() {
        val tenant = createTenant("Stencil read parity").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), letters), "Brand").execute()
            CreateStencil(StencilId(StencilKey.of("footer"), letters), "Footer", tags = listOf("legal"), content = usesHeaderAndBrand().copy(nodes = usesHeaderAndBrand().nodes - "header", slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = emptyList())))).execute()
        }
        val version = withMediator { mediator.releaseNext(letters) }
        val released = CatalogReleaseRef(letters, DraftRef.Released(version))

        fun content(list: List<Stencil>) = list.map { listOf(it.id, it.name, it.description, it.tags, it.content, it.parameterSchema, it.readsTemplateData) }
        withMediator {
            val drafted = ListStencils(TenantId(tenant), catalog = letters).query()
            assertThat(drafted).isNotEmpty()
            assertThat(content(ListStencils(TenantId(tenant), catalog = letters, catalogVersion = released).query())).isEqualTo(content(drafted))
        }
    }

    @Test
    fun `the migration converts a stencil as the suite stores it, both ways`() {
        val tenant = createTenant("Stencil conversion").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val parts = testCatalog(tenant, CatalogKey.of("parts"))
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateCatalog(tenant, parts.key, "Parts").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), letters), "Brand").execute()
            CreateStencil(StencilId(StencilKey.of("header"), parts), "Header").execute()
            CreateStencil(StencilId(StencilKey.of("footer"), letters), "Footer", description = "At the bottom", tags = listOf("legal"), content = usesHeaderAndBrand()).execute()
            CreateStencil(
                StencilId(StencilKey.of("address"), letters),
                "Address",
                parameterSchema = objectMapper.readTree("""{"type":"object","properties":{"street":{"type":"string"}}}"""),
                readsTemplateData = true,
            ).execute()
        }
        StencilStorageConversion.verify(jdbi, objectMapper, stencils, letters)
    }
}
