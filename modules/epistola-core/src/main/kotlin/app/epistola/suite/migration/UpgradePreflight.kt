// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import java.sql.Connection
import javax.sql.DataSource

/**
 * Before the upgrade from 1.x to 2.0 starts, whether it can finish.
 *
 * The upgrade gives every authored catalog a first release, each after the catalogs it uses
 * (`CatalogUpgradeRunner`). Two things would leave catalogs waiting for that release forever,
 * and nothing in them could render or be deployed:
 *
 * - **catalogs that use each other** (a dependency cycle), which 2.0 cannot release: each would
 *   have to pin the other's release, and neither can be released first;
 * - **a catalog the tenant does not have**, which its users would need to depend on.
 *
 * 1.x allowed both. They are found here, on the 1.x schema, before a single migration runs: a
 * migration that failed later could not undo the ones before it, and 1.x could no longer read the
 * half-converted database. Refused here, the database is untouched and 1.x keeps working, so it is
 * fixed there and the upgrade is run again.
 *
 * Read from what the upgrade keeps: each variant's and stencil's latest published version and its
 * draft, every theme, attribute definition and template, and the catalogs' details. Only an authored
 * catalog's references count: a subscribed catalog has its installed release recorded as it is, and
 * waits for nothing.
 */
object UpgradePreflight {

    /** What would stop the upgrade from finishing, by tenant; empty when nothing would. */
    data class Report(val cycles: List<Cycle>, val missing: List<MissingCatalog>) {
        val passed: Boolean get() = cycles.isEmpty() && missing.isEmpty()

        fun describe(): String = buildString {
            appendLine("The upgrade from 1.x cannot start: it would leave catalogs without the first release 2.0 renders from.")
            appendLine("Nothing was changed. Fix the following in 1.x, then run the upgrade again.")
            for (cycle in cycles) {
                appendLine()
                appendLine("Tenant '${cycle.tenantKey}': catalogs ${cycle.catalogs.joinToString(", ") { "'$it'" }} use each other, which 2.0 does not allow.")
                appendLine("  Move what they share into one of them, or into a catalog they all use. They use:")
                cycle.uses.forEach { appendLine("  - ${it.describe()}") }
            }
            for (missing in this@Report.missing) {
                appendLine()
                appendLine("Tenant '${missing.tenantKey}': catalog '${missing.catalogKey}' uses catalog '${missing.usedCatalog}', which this tenant does not have.")
                appendLine("  Install it, or change what uses it:")
                missing.uses.forEach { appendLine("  - ${it.describe()}") }
            }
        }.trimEnd()
    }

    /** One use of another catalog: [owner] (e.g. `template invoice`) of [catalogKey] uses [usedCatalog], through [via]. */
    data class Use(val catalogKey: String, val owner: String, val usedCatalog: String, val via: String) {
        fun describe(): String = "$owner in '$catalogKey' uses '$usedCatalog' ($via)"
    }

    data class Cycle(val tenantKey: String, val catalogs: List<String>, val uses: List<Use>)

    data class MissingCatalog(val tenantKey: String, val catalogKey: String, val usedCatalog: String, val uses: List<Use>)

    /** Refused before the migrations: thrown with the [report] that says what to fix. */
    class Refused(val report: Report) : IllegalStateException(report.describe())

    /** @throws Refused when the database is at 1.x and the upgrade could not finish. */
    fun require(dataSource: DataSource) {
        val report = dataSource.connection.use { if (isOneX(it)) check(it) else null } ?: return
        if (!report.passed) throw Refused(report)
    }

    /**
     * Whether the database still has the 1.x shape this reads: the version tables, and templates
     * addressed by catalog key. A database already at 2.0, or new, has neither, and is not checked.
     */
    fun isOneX(connection: Connection): Boolean = connection.createStatement().use { st ->
        st.executeQuery(
            """
            SELECT to_regclass('template_versions') IS NOT NULL AND to_regclass('stencil_versions') IS NOT NULL
               AND EXISTS (SELECT 1 FROM information_schema.columns
                           WHERE table_schema = current_schema() AND table_name = 'document_templates' AND column_name = 'catalog_key')
            """,
        ).use { rs -> rs.next() && rs.getBoolean(1) }
    }

    /** What would stop the upgrade, read from a 1.x database. */
    fun check(connection: Connection): Report {
        val authored = mutableMapOf<String, MutableSet<String>>()
        val known = mutableMapOf<String, MutableSet<String>>()
        connection.createStatement().use { st ->
            st.executeQuery("SELECT tenant_key, id, type FROM catalogs").use { rs ->
                while (rs.next()) {
                    known.getOrPut(rs.getString(1)) { mutableSetOf() } += rs.getString(2)
                    if (rs.getString(3) == "AUTHORED") authored.getOrPut(rs.getString(1)) { mutableSetOf() } += rs.getString(2)
                }
            }
        }
        val uses = mutableListOf<Pair<String, Use>>()
        connection.createStatement().use { st ->
            st.executeQuery(USES).use { rs ->
                while (rs.next()) {
                    val tenant = rs.getString("tenant_key")
                    val use = Use(rs.getString("catalog_key"), rs.getString("owner"), rs.getString("used_catalog"), rs.getString("via"))
                    if (use.usedCatalog != use.catalogKey && use.catalogKey in authored[tenant].orEmpty()) uses += tenant to use
                }
            }
        }
        val distinct = uses.distinct()

        val missing = distinct.filter { (tenant, use) -> use.usedCatalog !in known[tenant].orEmpty() }
            .groupBy { (tenant, use) -> Triple(tenant, use.catalogKey, use.usedCatalog) }
            .map { (key, group) -> MissingCatalog(key.first, key.second, key.third, group.map { it.second }.sortedBy { it.describe() }) }
            .sortedWith(compareBy({ it.tenantKey }, { it.catalogKey }, { it.usedCatalog }))

        val cycles = distinct.filter { (tenant, use) -> use.usedCatalog in authored[tenant].orEmpty() }
            .groupBy({ it.first }, { it.second })
            .flatMap { (tenant, tenantUses) ->
                cyclesOf(tenantUses.map { it.catalogKey to it.usedCatalog }.toSet()).map { members ->
                    Cycle(tenant, members, tenantUses.filter { it.catalogKey in members && it.usedCatalog in members }.sortedBy { it.describe() })
                }
            }
            .sortedWith(compareBy({ it.tenantKey }, { it.catalogs.first() }))
        return Report(cycles, missing)
    }

    /** The groups of catalogs that use each other: strongly connected components of two or more, sorted. */
    internal fun cyclesOf(edges: Set<Pair<String, String>>): List<List<String>> {
        val next = edges.groupBy({ it.first }, { it.second })
        val nodes = (edges.map { it.first } + edges.map { it.second }).toSortedSet()
        val index = mutableMapOf<String, Int>()
        val low = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = mutableSetOf<String>()
        val components = mutableListOf<List<String>>()
        var counter = 0
        fun visit(node: String) {
            index[node] = counter
            low[node] = counter
            counter++
            stack.addLast(node)
            onStack += node
            for (target in next[node].orEmpty().sorted()) {
                if (target !in index) {
                    visit(target)
                    low[node] = minOf(low.getValue(node), low.getValue(target))
                } else if (target in onStack) {
                    low[node] = minOf(low.getValue(node), index.getValue(target))
                }
            }
            if (low[node] == index[node]) {
                val component = mutableListOf<String>()
                do {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                } while (member != node)
                if (component.size > 1) components += component.sorted()
            }
        }
        nodes.forEach { if (it !in index) visit(it) }
        return components.sortedBy { it.first() }
    }

    /**
     * Every use of another catalog in a 1.x database: (tenant_key, catalog_key, owner, used_catalog,
     * via). A reference in content names its catalog as `catalogKey` wherever it sits (a stencil
     * instance, a theme override, an image, a font in theme styles, an attribute in the catalog's
     * details); a template's theme and an attribute's code list are foreign keys; a variant's
     * attributes are keyed `catalog.attribute`.
     */
    private val USES = """
        WITH kept_versions AS (
            SELECT DISTINCT ON (tv.tenant_key, tv.template_resource_id, tv.variant_key, tv.status)
                   tv.tenant_key, tv.template_resource_id, tv.variant_key, tv.template_model
            FROM template_versions tv
            WHERE tv.status IN ('draft', 'published')
            ORDER BY tv.tenant_key, tv.template_resource_id, tv.variant_key, tv.status, tv.id DESC
        ),
        kept_stencils AS (
            SELECT DISTINCT ON (sv.tenant_key, sv.stencil_resource_id, sv.status)
                   sv.tenant_key, sv.stencil_resource_id, sv.content
            FROM stencil_versions sv
            WHERE sv.status IN ('draft', 'published')
            ORDER BY sv.tenant_key, sv.stencil_resource_id, sv.status, sv.id DESC
        )
        SELECT dt.tenant_key, dt.catalog_key, 'template ' || dt.id || ' variant ' || kv.variant_key AS owner,
               ref #>> '{}' AS used_catalog, 'content' AS via
        FROM kept_versions kv
        JOIN document_templates dt ON dt.tenant_key = kv.tenant_key AND dt.resource_id = kv.template_resource_id
        CROSS JOIN LATERAL jsonb_path_query(kv.template_model, 'strict $.**.catalogKey') AS ref
        WHERE jsonb_typeof(ref) = 'string'
        UNION ALL
        SELECT s.tenant_key, s.catalog_key, 'stencil ' || s.id, ref #>> '{}', 'content'
        FROM kept_stencils ks
        JOIN stencils s ON s.tenant_key = ks.tenant_key AND s.resource_id = ks.stencil_resource_id
        CROSS JOIN LATERAL jsonb_path_query(ks.content, 'strict $.**.catalogKey') AS ref
        WHERE jsonb_typeof(ref) = 'string'
        UNION ALL
        SELECT th.tenant_key, th.catalog_key, 'theme ' || th.id, ref #>> '{}', 'styles'
        FROM themes th
        CROSS JOIN LATERAL jsonb_path_query(
            jsonb_build_array(th.document_styles, th.page_settings, th.block_style_presets), 'strict $.**.catalogKey') AS ref
        WHERE jsonb_typeof(ref) = 'string'
        UNION ALL
        SELECT c.tenant_key, c.id, 'catalog details', ref #>> '{}', 'attributes'
        FROM catalogs c
        CROSS JOIN LATERAL jsonb_path_query(COALESCE(c.catalog_metadata, '{}'::jsonb), 'strict $.**.catalogKey') AS ref
        WHERE jsonb_typeof(ref) = 'string'
        UNION ALL
        SELECT dt.tenant_key, dt.catalog_key, 'template ' || dt.id, r.catalog_key, 'theme ' || r.resource_key
        FROM document_templates dt
        JOIN catalog_resources r ON r.tenant_key = dt.tenant_key AND r.resource_id = dt.theme_resource_id
        UNION ALL
        SELECT ad.tenant_key, ad.catalog_key, 'attribute ' || ad.id, r.catalog_key, 'code list ' || r.resource_key
        FROM variant_attribute_definitions ad
        JOIN catalog_resources r ON r.tenant_key = ad.tenant_key AND r.resource_id = ad.code_list_resource_id
        UNION ALL
        SELECT dt.tenant_key, dt.catalog_key, 'template ' || dt.id || ' variant ' || v.id, split_part(attribute, '.', 1), 'attribute ' || attribute
        FROM template_variants v
        JOIN document_templates dt ON dt.tenant_key = v.tenant_key AND dt.resource_id = v.template_resource_id
        CROSS JOIN LATERAL jsonb_object_keys(COALESCE(v.attributes, '{}'::jsonb)) AS attribute
        WHERE position('.' IN attribute) > 0
    """
}
