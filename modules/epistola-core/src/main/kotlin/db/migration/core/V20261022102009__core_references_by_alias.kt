// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.sql.Connection

/**
 * Stored content names another catalog by alias (contract ADR 0003, amendment of 2026-10-22; ADR 0034).
 *
 * An earlier draft of wire v8 let a reference into another catalog carry that catalog's `namespace`
 * and `registry` beside its `catalogKey`: a stencil instance's props, a theme override (`themeRef`),
 * a `fontFamily` font reference in document, node, theme and preset styles. Content received in that
 * form was stored as it came. Now the `catalogKey` is the alias the catalog's dependency list
 * declares, the name is the declaration's, and the reference carries no qualifier; this installation
 * declares every dependency under its key (`V20261022101420`), so the `catalogKey` a reference keeps
 * is its alias, and it resolves to exactly the catalog it resolved to before: the tenant's catalog of
 * that key, which is unique in a tenant (`uq_catalogs_key_until_named`).
 *
 * Every working copy holding one loses its qualifiers: template variants' models, stencils' content,
 * themes' document styles and presets, and what the 2.0 upgrade cuts first releases from
 * (`catalog_upgrade_published`). Releases keep their retained content byte for byte (it is
 * immutable; reading drops the qualifiers). The meaning of nothing changes, so it is not an edit:
 * `updated_at` and the revision stay, and a ready mark that held keeps holding, recomputed against the
 * new content digest.
 *
 * A qualifier that does not name the tenant's catalog of that key (another namespace or registry, or a
 * key the tenant has no catalog for) cannot have resolved to anything else here: it is kept resolving
 * as before and reported, logged and in `app_metadata` under [REPORT_KEY].
 *
 * Works on JSON, not on the application's model, so that later code cannot change what it did.
 */
@Suppress("ClassName")
class V20261022102009__core_references_by_alias : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()
    private val report = mutableListOf<ObjectNode>()

    /** A catalog of a tenant, by key: its registry and namespace. */
    private data class Name(val registry: String, val namespace: String)

    override fun migrate(context: Context) {
        val connection = context.connection
        val names = catalogNames(connection)
        createHeldTable(connection)

        var changed = 0
        changed += rewrite(
            connection,
            select = """
                SELECT v.tenant_key, v.version_id::text, v.template_resource_id::text, v.id, c.catalog_key, v.template_model::text
                FROM template_variants v
                JOIN document_templates t ON t.tenant_key = v.tenant_key AND t.version_id = v.version_id AND t.resource_id = v.template_resource_id
                JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
                WHERE v.template_model::text ~ '"(namespace|registry)"'
            """,
            update = "UPDATE template_variants SET template_model = ?::jsonb WHERE tenant_key = ? AND version_id = ?::uuid AND template_resource_id = ?::uuid AND id = ?",
            names = names,
            what = { "template variant ${it[4]}/${it[2]}#${it[3]}" },
            part = { it[3] },
        )
        changed += rewrite(
            connection,
            select = """
                SELECT s.tenant_key, s.version_id::text, s.resource_id::text, '' AS part, c.catalog_key, s.content::text
                FROM stencils s JOIN catalogs c ON c.tenant_key = s.tenant_key AND c.catalog_id = s.catalog_id
                WHERE s.content::text ~ '"(namespace|registry)"'
            """,
            update = "UPDATE stencils SET content = ?::jsonb WHERE tenant_key = ? AND version_id = ?::uuid AND resource_id = ?::uuid",
            names = names,
            what = { "stencil ${it[4]}/${it[2]}" },
        )
        changed += rewriteThemes(connection, names)
        changed += rewrite(
            connection,
            select = """
                SELECT p.tenant_key, p.kind, p.resource_id::text, p.part, c.catalog_key, p.payload::text
                FROM catalog_upgrade_published p
                JOIN catalog_resources r ON r.tenant_key = p.tenant_key AND r.resource_id = p.resource_id
                JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
                WHERE p.payload::text ~ '"(namespace|registry)"'
            """,
            update = "UPDATE catalog_upgrade_published SET payload = ?::jsonb WHERE tenant_key = ? AND kind = ? AND resource_id = ?::uuid AND part = ?",
            names = names,
            what = { "published ${it[1]} of ${it[4]}/${it[2]}" },
            part = { it[3] },
            tracksReadiness = false,
        )

        keepHeldMarks(connection)

        if (report.isNotEmpty()) {
            connection.prepareStatement(
                "INSERT INTO app_metadata (key, value) VALUES (?, ?::jsonb) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()",
            ).use {
                it.setString(1, REPORT_KEY)
                it.setString(2, mapper.writeValueAsString(mapper.createArrayNode().addAll(report)))
                it.executeUpdate()
            }
        }
        log.info("References by alias: {} stored document(s) lost their namespace/registry qualifiers; {} notice(s) under {}", changed, report.size, REPORT_KEY)
    }

    /**
     * Rewrites the documents [select] finds, each row `tenant, version or kind, resource, part,
     * owning catalog key, json`, with [update] taking `json, tenant, version or kind, resource[, part]`.
     */
    private fun rewrite(
        connection: Connection,
        select: String,
        update: String,
        names: Map<String, Map<String, Name>>,
        what: (List<String>) -> String,
        part: ((List<String>) -> String)? = null,
        tracksReadiness: Boolean = true,
    ): Int {
        val rows = query(connection, select.trimIndent()) { rs -> (1..6).map { rs.getString(it) } }
        var changed = 0
        withoutTriggers(connection, update) {
            connection.prepareStatement(update).use { statement ->
                for (row in rows) {
                    val document = mapper.readTree(row[5])
                    if (!dropQualifiers(document, row[0], row[4], names, what(row))) continue
                    statement.setString(1, mapper.writeValueAsString(document))
                    statement.setString(2, row[0])
                    statement.setString(3, row[1])
                    statement.setString(4, row[2])
                    part?.let { statement.setString(5, it(row)) }
                    statement.addBatch()
                    if (tracksReadiness) markChanged(connection, row[0], row[1], row[2])
                    changed++
                }
                statement.executeBatch()
            }
        }
        return changed
    }

    private fun rewriteThemes(connection: Connection, names: Map<String, Map<String, Name>>): Int {
        val rows = query(
            connection,
            """
            SELECT th.tenant_key, th.version_id::text, th.resource_id::text, c.catalog_key, th.document_styles::text, th.block_style_presets::text
            FROM themes th JOIN catalogs c ON c.tenant_key = th.tenant_key AND c.catalog_id = th.catalog_id
            WHERE COALESCE(th.document_styles::text, '') ~ '"(namespace|registry)"' OR COALESCE(th.block_style_presets::text, '') ~ '"(namespace|registry)"'
            """.trimIndent(),
        ) { rs -> (1..6).map { rs.getString(it) } }
        var changed = 0
        val update = "UPDATE themes SET document_styles = ?::jsonb, block_style_presets = ?::jsonb WHERE tenant_key = ? AND version_id = ?::uuid AND resource_id = ?::uuid"
        withoutTriggers(connection, update) {
            connection.prepareStatement(update).use { statement ->
                for (row in rows) {
                    val styles = row[4]?.let(mapper::readTree)
                    val presets = row[5]?.let(mapper::readTree)
                    val where = "theme ${row[3]}/${row[2]}"
                    val dropped = listOfNotNull(styles, presets).map { dropQualifiers(it, row[0], row[3], names, where) }.any { it }
                    if (!dropped) continue
                    statement.setString(1, styles?.let(mapper::writeValueAsString))
                    statement.setString(2, presets?.let(mapper::writeValueAsString))
                    statement.setString(3, row[0])
                    statement.setString(4, row[1])
                    statement.setString(5, row[2])
                    statement.addBatch()
                    markChanged(connection, row[0], row[1], row[2])
                    changed++
                }
                statement.executeBatch()
            }
        }
        return changed
    }

    /**
     * Drops `namespace` and `registry` from every reference in [document] (owned by catalog [owner]
     * of [tenant]), reporting those that did not name the tenant's catalog of their key.
     * @return true when any reference carried one.
     */
    private fun dropQualifiers(document: JsonNode, tenant: String, owner: String, names: Map<String, Map<String, Name>>, where: String): Boolean {
        var dropped = false
        for (holder in references(document)) {
            if (!holder.has(NAMESPACE) && !holder.has(REGISTRY)) continue
            val catalogKey = holder.path(CATALOG_KEY).takeIf { it.isString }?.asString() ?: owner
            val ownName = names[tenant]?.get(owner)
            val namespace = holder.path(NAMESPACE).takeIf { it.isString }?.asString() ?: ownName?.namespace
            val registry = holder.path(REGISTRY).takeIf { it.isString }?.asString() ?: ownName?.registry
            val target = names[tenant]?.get(catalogKey)
            if (target == null || target.namespace != namespace || target.registry != registry) {
                note(
                    tenant,
                    where,
                    "a reference named catalog '$catalogKey' in $registry/$namespace, but " +
                        (target?.let { "this tenant's catalog '$catalogKey' is in ${it.registry}/${it.namespace}" } ?: "this tenant has no catalog '$catalogKey'") +
                        "; it keeps resolving by key, as it did",
                )
            }
            holder.remove(NAMESPACE)
            holder.remove(REGISTRY)
            dropped = true
        }
        return dropped
    }

    /**
     * The objects in [root] that carry a reference into a catalog, as the suite's reference sites find
     * them (`ResourceReferenceSites`): a stencil's or image's node props, a `themeRef`, a `fontFamily`.
     */
    private fun references(root: JsonNode): List<ObjectNode> = buildList {
        fun walk(node: JsonNode) {
            if (node is ObjectNode) {
                val type = node.path("type").takeIf { it.isString }?.asString()
                if (type == "stencil" || type == "image") (node.get("props") as? ObjectNode)?.let(::add)
                (node.get("themeRef") as? ObjectNode)?.takeIf { it.has("themeKey") || it.has("themeId") }?.let(::add)
                (node.get("fontFamily") as? ObjectNode)?.takeIf { it.has("key") || it.has("slug") }?.let(::add)
                node.properties().forEach { (_, child) -> walk(child) }
            } else if (node.isArray) {
                node.forEach(::walk)
            }
        }
        walk(root)
    }

    /** Runs [block] with the timestamp and revision triggers of [update]'s table off: dropping a qualifier is not an edit. */
    private fun withoutTriggers(connection: Connection, update: String, block: () -> Unit) {
        val table = update.substringAfter("UPDATE ").substringBefore(' ')
        val triggers = TRIGGERS[table].orEmpty()
        triggers.forEach { connection.createStatement().use { s -> s.execute("ALTER TABLE $table DISABLE TRIGGER $it") } }
        try {
            block()
        } finally {
            triggers.forEach { connection.createStatement().use { s -> s.execute("ALTER TABLE $table ENABLE TRIGGER $it") } }
        }
    }

    /** The ready marks of rewritten resources that held before, to keep holding ([keepHeldMarks]). */
    private fun createHeldTable(connection: Connection) {
        connection.createStatement().use {
            it.execute("CREATE TEMP TABLE alias_migration_held (tenant_key TEXT, version_id UUID, resource_id UUID, part TEXT) ON COMMIT DROP")
        }
    }

    /** Remembers the ready marks of a resource that hold now, before its content is rewritten. */
    private fun markChanged(connection: Connection, tenant: String, version: String, resource: String) {
        connection.prepareStatement(
            """
            INSERT INTO alias_migration_held (tenant_key, version_id, resource_id, part)
            SELECT m.tenant_key, m.version_id, m.resource_id, m.part
            FROM resource_ready_marks m
            JOIN resource_content_digests d ON d.tenant_key = m.tenant_key AND d.version_id = m.version_id
                                           AND d.resource_id = m.resource_id AND d.part = m.part
            WHERE m.tenant_key = ? AND m.version_id = ?::uuid AND m.resource_id = ?::uuid AND d.digest = m.digest
              AND NOT EXISTS (SELECT 1 FROM alias_migration_held h WHERE h.tenant_key = m.tenant_key AND h.version_id = m.version_id
                                                                    AND h.resource_id = m.resource_id AND h.part = m.part)
            """.trimIndent(),
        ).use {
            it.setString(1, tenant)
            it.setString(2, version)
            it.setString(3, resource)
            it.executeUpdate()
        }
    }

    /** Every ready mark that held before keeps holding: its digest is the content's as it is now. */
    private fun keepHeldMarks(connection: Connection) {
        connection.createStatement().use {
            it.executeUpdate(
                """
                UPDATE resource_ready_marks m SET digest = d.digest
                FROM alias_migration_held h, resource_content_digests d
                WHERE h.tenant_key = m.tenant_key AND h.version_id = m.version_id AND h.resource_id = m.resource_id AND h.part = m.part
                  AND d.tenant_key = m.tenant_key AND d.version_id = m.version_id AND d.resource_id = m.resource_id AND d.part = m.part
                  AND d.digest <> m.digest
                """.trimIndent(),
            )
        }
    }

    private fun catalogNames(connection: Connection): Map<String, Map<String, Name>> = query(connection, "SELECT tenant_key, catalog_key, registry, namespace FROM catalogs") { rs ->
        Triple(rs.getString(1), rs.getString(2), Name(rs.getString(3), rs.getString(4)))
    }.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, v) -> v.toMap() }

    private fun note(tenant: String, where: String, message: String) {
        log.warn("References by alias, tenant {}, {}: {}", tenant, where, message)
        report += mapper.createObjectNode().put("tenant", tenant).put("where", where).put("message", message)
    }

    private fun <T> query(connection: Connection, sql: String, map: (java.sql.ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

    companion object {
        const val REPORT_KEY = "references-by-alias"
        private const val CATALOG_KEY = "catalogKey"
        private const val NAMESPACE = "namespace"
        private const val REGISTRY = "registry"

        /** The triggers that stamp an update as an edit, per table this migration rewrites. */
        private val TRIGGERS = mapOf(
            "template_variants" to listOf("trg_template_variants_updated_at", "trg_template_variants_revision"),
            "stencils" to listOf("trg_stencils_updated_at", "trg_stencils_revision"),
            "themes" to listOf("trg_themes_updated_at", "trg_themes_revision"),
        )
    }
}
