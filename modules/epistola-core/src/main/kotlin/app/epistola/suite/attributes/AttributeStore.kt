// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CodeListBindingRef
import app.epistola.suite.attributes.codelists.CodeListNotFoundException
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.catalog.revisions.StoredContent
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The columns an attribute read selects from `current_attributes` aliased `t`, mapped by
 * [AttributeRows]. The bound code list's catalog is the one its alias resolves to
 * (`resolve_catalog_alias`), selected once; catalog names come from the mapper, not from joins or
 * per-row lookups (ThemePlanStabilityTest).
 */
const val ATTRIBUTE_COLUMNS: String =
    "t.id, t.tenant_key, t.catalog_id, t.display_name, t.allowed_values::text AS allowed_values, " +
        "t.code_list_catalog_id, t.code_list_key, t.created_at, t.updated_at, t.revision"

/** Maps a row of [ATTRIBUTE_COLUMNS], naming catalogs from the tenant's, read once per statement. */
class AttributeRows private constructor(
    private val objectMapper: ObjectMapper,
    private val catalogs: Map<UUID, CatalogId>,
) : org.jdbi.v3.core.mapper.RowMapper<VariantAttributeDefinition> {
    override fun map(rs: java.sql.ResultSet, ctx: org.jdbi.v3.core.statement.StatementContext): VariantAttributeDefinition {
        val tenantKey = app.epistola.suite.common.ids.TenantKey.of(rs.getString("tenant_key"))
        val catalog = catalogOf(rs.getObject("catalog_id", UUID::class.java), tenantKey)
        val boundCatalog = rs.getObject("code_list_catalog_id", UUID::class.java)?.let { catalogs[it] }
        val boundKey = rs.getString("code_list_key")?.takeIf { boundCatalog != null }
        return VariantAttributeDefinition(
            id = AttributeKey.of(rs.getString("id")),
            tenantKey = tenantKey,
            catalogKey = catalog.key,
            displayName = rs.getString("display_name"),
            allowedValues = objectMapper.readValue(rs.getString("allowed_values"), objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
            codeListCatalogKey = boundCatalog?.key,
            codeListSlug = boundKey?.let(app.epistola.suite.common.ids.CodeListKey::of),
            namespace = catalog.namespace,
            codeListCatalogNamespace = boundCatalog?.namespace,
            createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java),
            updatedAt = rs.getObject("updated_at", java.time.OffsetDateTime::class.java),
            revision = rs.getLong("revision"),
        )
    }

    private fun catalogOf(id: UUID, tenantKey: app.epistola.suite.common.ids.TenantKey): CatalogId = checkNotNull(catalogs[id]) { "Catalog $id of tenant ${tenantKey.value} is gone" }

    companion object {
        /** For a read of [tenantKey]'s attributes: its catalogs' addresses, read once. */
        fun of(handle: Handle, objectMapper: ObjectMapper, tenantKey: app.epistola.suite.common.ids.TenantKey): AttributeRows {
            val catalogs = handle.createQuery("SELECT catalog_id, namespace, catalog_key FROM catalogs WHERE tenant_key = :t")
                .bind("t", tenantKey)
                .map { rs, _ -> rs.getObject("catalog_id", UUID::class.java) to CatalogId(rs.getString("namespace"), app.epistola.suite.catalog.CatalogKey.of(rs.getString("catalog_key")), app.epistola.suite.common.ids.TenantId(tenantKey)) }
                .list()
                .toMap()
            return AttributeRows(objectMapper, catalogs)
        }
    }
}

/** What an attribute says: everything its content holds but its key. */
data class AttributeFields(
    val name: String,
    val allowedValues: List<String>,
    /** The code list it is bound to, by the alias its catalog knows it under (null: its own) and key. */
    val binding: CodeListBindingRef?,
)

/**
 * Writes the variant attributes of a draft (ADR 0033, step 3): `{ "resource": <AttributeResource> }`
 * in `resource_contents`, and the draft's row naming it ([DraftResources]). A code list binding is
 * the wire form's alias and key; it is indexed in `resource_references` when stored, and checked here
 * on write, as a foreign key used to.
 */
@Component
class AttributeStore(
    private val objectMapper: ObjectMapper,
    private val contents: ResourceContentStore,
) {

    /** The content of attribute [key]. */
    fun payload(key: String, fields: AttributeFields): JsonNode = objectMapper.createObjectNode().set(
        "resource",
        objectMapper.valueToTree<JsonNode>(AttributeResource(key, fields.name, fields.allowedValues, fields.binding)),
    )

    /**
     * The binding attribute content of [owner] holds for code list [codeList]: no alias for its own
     * catalog, else the list's catalog key (every dependency is declared under its key here). Refuses
     * a code list that has no identity at its address, as the foreign key on the identity did.
     */
    fun bindingOf(handle: Handle, owner: CatalogId, codeList: CodeListId): CodeListBindingRef {
        val exists = handle.createQuery(
            "SELECT EXISTS (SELECT 1 FROM catalog_resources WHERE tenant_key = :t AND resource_type = 'codeList' AND catalog_id = ${catalogAt("t", "c")} AND resource_key = :k)",
        ).bind("t", codeList.tenantKey).bindCatalog("c", codeList.catalogId).bind("k", codeList.key.value).mapTo(Boolean::class.java).one()
        if (!exists) throw CodeListNotFoundException(codeList.catalogId, codeList.key)
        return CodeListBindingRef(catalogKey = codeList.catalogKey.value.takeUnless { codeList.catalogId == owner }, key = codeList.key.value)
    }

    fun store(handle: Handle, catalog: CatalogId, key: String, fields: AttributeFields): String = contents.store(handle, catalog.tenantKey, ATTRIBUTE, payload(key, fields))

    /** Adds attribute [id] to the targeted draft; one already there is a unique violation. */
    fun create(handle: Handle, id: AttributeId, fields: AttributeFields, actor: UUID?): VariantAttributeDefinition {
        DraftResources.insert(handle, id.catalogId, ATTRIBUTE, id.key.value, store(handle, id.catalogId, id.key.value, fields), fields.name, null, actor)
        return checkNotNull(get(handle, id)) { "Attribute ${id.key.value} was just written" }
    }

    /** Saves [fields] as attribute [id] of the targeted draft; false when it holds none, or it moved on. */
    fun write(handle: Handle, id: AttributeId, fields: AttributeFields, actor: UUID?, expectedRevision: Long? = null): Boolean = DraftResources.update(handle, id.catalogId, ATTRIBUTE, id.key.value, store(handle, id.catalogId, id.key.value, fields), fields.name, null, actor, expectedRevision) != null

    /** Creates or replaces attribute [id] in the targeted draft; true when it was there already. */
    fun put(handle: Handle, id: AttributeId, fields: AttributeFields, actor: UUID?): Boolean {
        if (write(handle, id, fields, actor)) return true
        DraftResources.insert(handle, id.catalogId, ATTRIBUTE, id.key.value, store(handle, id.catalogId, id.key.value, fields), fields.name, null, actor)
        return false
    }

    fun delete(handle: Handle, id: AttributeId): Boolean = DraftResources.delete(handle, id.catalogId, ATTRIBUTE, id.key.value)

    /** Moves attribute [key] of [source] to [targetKey] of [target], with its content renamed; false when there is none. */
    fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean {
        val fields = fields(handle, AttributeId(AttributeKey.of(key), source)) ?: return false
        return DraftResources.move(handle, source, ATTRIBUTE, key, target, targetKey, store(handle, source, targetKey, fields))
    }

    /** Attribute [id] as the version the handle means holds it, or null. */
    fun get(handle: Handle, id: AttributeId): VariantAttributeDefinition? = handle
        .createQuery("SELECT $ATTRIBUTE_COLUMNS FROM current_attributes t WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")} AND t.id = :k")
        .bind("t", id.tenantKey)
        .bindCatalog("c", id.catalogId)
        .bind("k", id.key.value)
        .map(AttributeRows.of(handle, objectMapper, id.tenantKey))
        .findOne()
        .orElse(null)

    /** What attribute [id] says, read from its content; null when the version holds none. */
    fun fields(handle: Handle, id: AttributeId): AttributeFields? = handle
        .createQuery("SELECT t.payload::text FROM current_attributes t WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")} AND t.id = :k")
        .bind("t", id.tenantKey)
        .bindCatalog("c", id.catalogId)
        .bind("k", id.key.value)
        .mapTo(String::class.java)
        .findOne()
        .orElse(null)
        ?.let { fieldsOf(objectMapper.readTree(it)) }

    /** What a stored content says. */
    fun fieldsOf(payload: JsonNode): AttributeFields {
        val resource = objectMapper.treeToValue(StoredContent.resourceOf(payload), AttributeResource::class.java)
        return AttributeFields(resource.name, resource.allowedValues, resource.codeListBinding)
    }

    companion object {
        /** The resource type, and content kind, of a variant attribute. */
        const val ATTRIBUTE = "attribute"
    }
}
