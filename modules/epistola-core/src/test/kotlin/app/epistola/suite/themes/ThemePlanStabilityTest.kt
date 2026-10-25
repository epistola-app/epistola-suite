// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.testing.IntegrationTestBase
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
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The reads of the types stored as JSON plan well before the planner has statistics on the storage
 * tables (ADR 0033).
 *
 * A fresh installation starts with `release_resources` and `resource_contents` empty. pgjdbc
 * server-prepares a statement after its fifth execution and Postgres may keep a generic plan made
 * while the tables had no statistics. The plan that went wrong put `resource_contents` (estimated
 * at one row) outermost in a nested loop and rescanned the tenant's `release_resources` rows once
 * per stored content: releasing a catalog went from 0.3 s to 8 s over 25 catalogs. The reads now go
 * through `catalog_version_resources(type)`, whose shape is fixed.
 *
 * This holds the shape, not a time, so machine load cannot fail it: on its own freshly migrated
 * database, with autoanalyze off on the storage tables, the generic plan of each read must not have
 * that nested loop. A copy of the join the reads used before shows the check finds it.
 * The time it takes is measured in the perf tests only.
 */
@TestPropertySource(properties = ["epistola.test.context=theme-plan-stability"])
class ThemePlanStabilityTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val storage = listOf("release_resources", "resource_contents", "resource_references", "catalog_resources", "resource_credentials", "code_list_refreshes")

    private fun autoanalyze(on: Boolean) = jdbi.useHandle<Exception> { h ->
        storage.forEach { h.execute("ALTER TABLE $it SET (autovacuum_enabled = $on)") }
    }

    /**
     * The generic plan of [sql], as JSON: prepared, as pgjdbc prepares a statement it runs often, and
     * planned with `plan_cache_mode = force_generic_plan`, so the values bound to it ([values]: $1
     * tenant, $2 namespace, $3 catalog key, $4 resource key) do not shape it.
     */
    private fun genericPlan(sql: String, values: List<String>): JsonNode = jdbi.inTransaction<JsonNode, Exception> { h ->
        h.execute("SET LOCAL plan_cache_mode = force_generic_plan")
        h.execute("PREPARE plan_check AS $sql")
        try {
            val arguments = values.take(PARAMETER.findAll(sql).map { it.value }.toSet().size).joinToString(", ") { "'${it.replace("'", "''")}'" }
            val json = h.createQuery("EXPLAIN (FORMAT JSON) EXECUTE plan_check($arguments)").mapTo(String::class.java).one()
            objectMapper.readTree(json).get(0).get("Plan")
        } finally {
            h.execute("DEALLOCATE plan_check")
        }
    }

    private fun relations(plan: JsonNode): Set<String> = buildSet {
        plan.get("Relation Name")?.asString()?.let(::add)
        plan.get("Plans")?.forEach { addAll(relations(it)) }
    }

    /**
     * The relations [plan] scans, but for the lookup of one template's variant rows by its identity
     * (`idx_release_resources_parent`, ADR 0033): that reads a template's few rows, not the tenant's.
     */
    private fun scanned(plan: JsonNode): Set<String> = buildSet {
        val variantLookup = plan.get("Index Name")?.asString() == "idx_release_resources_parent"
        plan.get("Relation Name")?.asString()?.takeUnless { variantLookup }?.let(::add)
        plan.get("Plans")?.forEach { addAll(scanned(it)) }
    }

    /** Every nested loop that rescans `release_resources` once per row of `resource_contents`. */
    private fun rescans(plan: JsonNode): List<String> = buildList {
        val children = plan.get("Plans")?.toList().orEmpty()
        if (plan.get("Node Type")?.asString() == "Nested Loop") {
            val outer = children.firstOrNull { it.get("Parent Relationship")?.asString() == "Outer" }
            val inner = children.firstOrNull { it.get("Parent Relationship")?.asString() == "Inner" }
            if (outer != null && inner != null && "resource_contents" in relations(outer) && "release_resources" in scanned(inner)) {
                add("Nested Loop: outer ${relations(outer)}, inner ${relations(inner)}")
            }
        }
        children.forEach { addAll(rescans(it)) }
    }

    @Test
    fun `the reads of every type stored as JSON never rescan the rows per stored content, on tables without statistics`() {
        autoanalyze(false)
        try {
            val tenant = withMediator { createTenant("Theme plan stability").id }
            val first = testCatalog(tenant, CatalogKey.of("cat-0"))
            val values = listOf(tenant.value, first.namespace, first.key.value, "theme-0")
            // Planned while the tables hold only the system catalog, as a fresh node's first reads are
            // (where the plan that went wrong was made), and again once they have grown.
            val early = plans(values)
            withMediator {
                (0 until CATALOGS).forEach { c ->
                    val key = CatalogKey.of("cat-$c")
                    CreateCatalog(tenantKey = tenant, id = key, name = "Catalog $c").execute()
                    val catalog = testCatalog(tenant, key)
                    repeat(THEMES) { t -> CreateTheme(ThemeId(ThemeKey.of("theme-$t"), catalog), "Theme $c/$t").execute() }
                    repeat(CODE_LISTS) { l ->
                        CreateCodeList(CodeListId(CodeListKey.of("list-$l"), catalog), "List $c/$l", sourceType = CodeListSource.INLINE, entries = (0 until 20).map { CodeListEntry("c$it", "Entry $it", it) }).execute()
                        CreateAttributeDefinition(AttributeId(AttributeKey.of("attr-$l"), catalog), "Attribute $c/$l", codeListId = CodeListId(CodeListKey.of("list-$l"), catalog)).execute()
                    }
                    repeat(STENCILS) { s ->
                        CreateStencil(StencilId(StencilKey.of("stencil-$s"), catalog), "Stencil $c/$s", tags = listOf("tag-${s % 3}"), content = themed("theme-${s % THEMES}")).execute()
                    }
                    repeat(TEMPLATES) { t ->
                        app.epistola.suite.templates.commands.CreateDocumentTemplate(app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of("template-$t"), catalog), "Template $c/$t").execute()
                    }
                    mediator.releaseNext(catalog)
                }
            }
            val grown = plans(values)

            for ((name, plan) in early + grown) {
                if (name == BEFORE) continue
                assertThat(rescans(plan)).`as`("$name: %s", plan).isEmpty()
            }
            // The read the themes had before the fix: the check finds the plan that went wrong.
            assertThat(listOf(early, grown).flatMap { rescans(it.getValue(BEFORE)) }).`as`("the read before the fix: %s", early.getValue(BEFORE)).isNotEmpty()
        } finally {
            autoanalyze(true)
        }
    }

    /** The generic plan of every read, and of the read before the fix ([BEFORE]), by name. */
    private fun plans(values: List<String>): Map<String, JsonNode> {
        val catalog = "t.tenant_key = $1 AND t.catalog_id = epistola_catalog_id($1, $2, $3)"
        val reads = listOf("current_themes", "current_code_lists", "current_attributes", "current_stencils").flatMap { view ->
            listOf(
                "$view of a catalog" to "SELECT t.payload FROM $view t WHERE $catalog",
                "$view, one" to "SELECT t.payload FROM $view t WHERE $catalog AND t.id = $4",
            )
        } + listOf(
            // Templates read through views of the same shape before they move (ADR 0033, step 7a).
            "current_templates of a catalog" to "SELECT t.id, t.name, t.theme_key FROM current_templates t WHERE $catalog",
            "current_templates, one" to "SELECT t.id, t.data_model FROM current_templates t WHERE $catalog AND t.id = $4",
            "current_template_variants of a catalog" to "SELECT t.id, t.template_model FROM current_template_variants t WHERE $catalog",
        ) + (BEFORE to BEFORE_SQL)
        return reads.associate { (name, sql) -> name to genericPlan(sql, values) }
    }

    private companion object {
        val PARAMETER = Regex("""\$\d""")
        const val BEFORE = "the read before the fix"

        /**
         * A catalog's themes as `ExportThemes` read them before the fix (`V20261024114500` as first
         * written, ADR 0033 step 1b): the version's rows from a view of two unions, joined to their
         * content and their catalog, so the planner chose the join order.
         */
        val BEFORE_SQL = """
            SELECT c.payload, k.catalog_key FROM (
                SELECT r.* FROM release_resources r
                WHERE r.version_id IS NOT NULL
                  AND r.version_id IN (SELECT cv.version_id FROM catalog_current_versions cv WHERE cv.tenant_key = r.tenant_key)
                  AND r.catalog_id IS DISTINCT FROM NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
                UNION ALL
                SELECT r.* FROM release_resources r
                WHERE r.version IS NOT NULL
                  AND r.catalog_id = NULLIF(current_setting('epistola.release_catalog', TRUE), '')::uuid
                  AND r.version = current_setting('epistola.release_version', TRUE)
            ) r
            JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
            JOIN catalogs k ON k.tenant_key = r.tenant_key AND k.catalog_id = r.catalog_id
            WHERE r.resource_type = 'theme' AND r.tenant_key = $1 AND r.catalog_id = epistola_catalog_id($1, $2, $3)
        """
        const val CATALOGS = 5
        const val THEMES = 25
        const val CODE_LISTS = 5
        const val STENCILS = 10
        const val TEMPLATES = 5

        fun themed(theme: String) = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("children"))),
            slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = emptyList())),
            themeRef = ThemeRefOverride(themeKey = theme),
        )
    }
}
