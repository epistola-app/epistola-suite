// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core.frozen

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.sql.Connection

/**
 * The template and stencil content of a 1.x installation, in wire v8 ([TemplateContentV8]), as the
 * 2.0 upgrade reads it from the 1.x version tables, frozen: those tables are the last 1.x schema, which
 * never changes. Several steps read it (step 6 for the images it names, 7, 8, 9); it is deterministic,
 * so each reads the same.
 *
 * 1.x kept versions per template variant, data contract and stencil. 2.0 keeps one working copy of
 * each, what its author was working on: its draft, else its latest published version, else its
 * latest archived one, else the empty document a new one starts with. And what each last published,
 * which the upgrade drafts as "Published in 1.x".
 */
object OneXContent {

    /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
    const val CHECKSUM: Int = -308414232

    private val mapper = CanonicalJson.mapper

    /** The document a new template variant or stencil starts with. */
    val EMPTY_DOCUMENT: JsonNode = mapper.readTree(
        """{"modelVersion": 1, "root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["slot-root"]}}, "slots": {"slot-root": {"id": "slot-root", "nodeId": "root", "name": "children", "children": []}}, "themeRef": {"type": "inherit"}}""",
    )

    /** A 1.x data contract version. */
    class ContractVersion(val tenantKey: String, val templateResourceId: String, val id: Int, val status: String, val dataModel: JsonNode?, val dataExamples: JsonNode?)

    /** What the 1.x versions become. */
    class Content(
        private val templateVersions: List<TemplateContentV8.TemplateVersion>,
        private val statuses: Map<String, String>,
        private val stencilVersions: List<TemplateContentV8.StencilVersion>,
        private val contracts: List<ContractVersion>,
        val converted: TemplateContentV8.Result,
    ) {
        /** A variant's working model in v8: its draft, else latest published, else latest archived version; else the empty document. */
        fun workingModel(tenantKey: String, templateResourceId: String, variantKey: String): JsonNode = versionsOf(tenantKey, templateResourceId, variantKey)
            .minWithOrNull(compareBy<TemplateContentV8.TemplateVersion> { rank(statusOf(it)) }.thenByDescending { it.id })
            ?.let { converted.models.getValue(TemplateContentV8.key(it.tenantKey, it.templateResourceId, it.variantKey, it.id)) }
            ?: EMPTY_DOCUMENT

        /** A variant's latest published model in v8; null when it was never published. */
        fun publishedModel(tenantKey: String, templateResourceId: String, variantKey: String): JsonNode? = versionsOf(tenantKey, templateResourceId, variantKey)
            .filter { statusOf(it) == "published" }
            .maxByOrNull { it.id }
            ?.let { converted.models.getValue(TemplateContentV8.key(it.tenantKey, it.templateResourceId, it.variantKey, it.id)) }

        /** A template's working data contract: its draft, else its latest other version; null when it has none. */
        fun workingContract(tenantKey: String, templateResourceId: String): ContractVersion? = contracts
            .filter { it.tenantKey == tenantKey && it.templateResourceId == templateResourceId }
            .minWithOrNull(compareBy<ContractVersion> { if (it.status == "draft") 0 else 1 }.thenByDescending { it.id })

        /** A template's latest published data contract; null when none was published. */
        fun publishedContract(tenantKey: String, templateResourceId: String): ContractVersion? = contracts
            .filter { it.tenantKey == tenantKey && it.templateResourceId == templateResourceId && it.status == "published" }
            .maxByOrNull { it.id }

        /** Whether a variant has a draft that differs from what it last published (unpublished work). */
        fun hasUnpublishedModel(tenantKey: String, templateResourceId: String, variantKey: String): Boolean {
            val versions = versionsOf(tenantKey, templateResourceId, variantKey)
            val published = versions.filter { statusOf(it) == "published" }.maxByOrNull { it.id }
            return versions.any { statusOf(it) == "draft" && (published == null || converted.models[keyOf(it)] != converted.models[keyOf(published)]) }
        }

        /** Whether a template has a draft data contract that differs from the one it last published. */
        fun hasUnpublishedContract(tenantKey: String, templateResourceId: String): Boolean {
            val published = publishedContract(tenantKey, templateResourceId) ?: return false
            return contracts.any {
                it.tenantKey == tenantKey && it.templateResourceId == templateResourceId && it.status == "draft" &&
                    (json(it.dataModel) != json(published.dataModel) || json(it.dataExamples) != json(published.dataExamples))
            }
        }

        /** Whether a stencil has a draft version. */
        fun hasStencilDraft(tenantKey: String, resourceId: String): Boolean = stencilVersions.any { it.tenantKey == tenantKey && it.resourceId == resourceId && it.status == "draft" }

        /** A stencil in v8: its working copy, and its latest published version. */
        fun stencil(tenantKey: String, resourceId: String): TemplateContentV8.ConvertedStencil = converted.stencils.getValue(TemplateContentV8.key(tenantKey, resourceId))

        /** SQL NULL and JSON null compare alike, as they did in the version tables (jsonb_build_array). */
        private fun json(node: JsonNode?): JsonNode = node ?: mapper.nullNode()

        private fun keyOf(v: TemplateContentV8.TemplateVersion) = TemplateContentV8.key(v.tenantKey, v.templateResourceId, v.variantKey, v.id)

        private fun statusOf(v: TemplateContentV8.TemplateVersion) = statuses.getValue(keyOf(v))

        private fun versionsOf(tenantKey: String, templateResourceId: String, variantKey: String) = templateVersions.filter {
            it.tenantKey == tenantKey && it.templateResourceId == templateResourceId && it.variantKey == variantKey
        }

        private fun rank(status: String) = when (status) {
            "draft" -> 0
            "published" -> 1
            else -> 2
        }
    }

    /** Reads every 1.x template version, data contract and stencil version and converts them. */
    fun load(connection: Connection): Content {
        val stencilVersions = query(
            connection,
            "SELECT tenant_key, stencil_resource_id::text, id, status, content::text, parameter_schema::text FROM stencil_versions ORDER BY tenant_key, stencil_resource_id, id",
        ) { rs ->
            TemplateContentV8.StencilVersion(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), mapper.readTree(rs.getString(5)), rs.getString(6)?.let(mapper::readTree))
        }
        val stencils = query(
            connection,
            "SELECT s.tenant_key, s.resource_id::text, s.catalog_key, s.id FROM stencils s ORDER BY s.tenant_key, s.catalog_key, s.id",
        ) { rs ->
            val tenant = rs.getString(1)
            val resourceId = rs.getString(2)
            // Its working copy: its draft, else its latest published version, else its latest archived one.
            val working = stencilVersions.filter { it.tenantKey == tenant && it.resourceId == resourceId }
                .minWithOrNull(
                    compareBy<TemplateContentV8.StencilVersion> {
                        if (it.status == "draft") {
                            0
                        } else if (it.status == "published") {
                            1
                        } else {
                            2
                        }
                    }.thenByDescending { it.id },
                )
            TemplateContentV8.Stencil(
                tenant,
                resourceId,
                rs.getString(3),
                rs.getString(4),
                (working?.content as? ObjectNode) ?: EMPTY_DOCUMENT.deepCopy() as ObjectNode,
                working?.parameterSchema?.takeUnless { it.isNull },
            )
        }
        val statuses = mutableMapOf<String, String>()
        val templateVersions = query(
            connection,
            """
            SELECT v.tenant_key, v.template_resource_id::text, v.variant_key, v.id, v.template_model::text, t.catalog_key, t.id, v.status
            FROM template_versions v
            JOIN document_templates t ON t.tenant_key = v.tenant_key AND t.resource_id = v.template_resource_id
            ORDER BY v.tenant_key, v.template_resource_id, v.variant_key, v.id
            """,
        ) { rs ->
            TemplateContentV8.TemplateVersion(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), mapper.readTree(rs.getString(5)), rs.getString(6), rs.getString(7))
                .also { statuses[TemplateContentV8.key(it.tenantKey, it.templateResourceId, it.variantKey, it.id)] = rs.getString(8) }
        }
        val contracts = query(
            connection,
            "SELECT tenant_key, template_resource_id::text, id, status, data_model::text, data_examples::text FROM contract_versions ORDER BY tenant_key, template_resource_id, id",
        ) { rs ->
            ContractVersion(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                rs.getString(4),
                rs.getString(5)?.let(mapper::readTree),
                DataExampleKeys.keyedByName(rs.getString(6)?.let(mapper::readTree)),
            )
        }
        return Content(templateVersions, statuses, stencilVersions, contracts, TemplateContentV8.convert(stencils, stencilVersions, templateVersions))
    }

    private fun <T> query(connection: Connection, sql: String, map: (java.sql.ResultSet) -> T): List<T> = connection.prepareStatement(sql).use { statement ->
        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }
}
