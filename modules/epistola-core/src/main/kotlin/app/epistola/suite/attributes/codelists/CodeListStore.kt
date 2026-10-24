// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.catalog.protocol.CodeListEntryEntry
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.catalog.revisions.StoredContent
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.crypto.Secret
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The columns a code list read selects from `current_code_lists` aliased `t`, named as [CodeList]
 * maps them. The catalog's name, the credential and the last refresh are looked up by key, row by
 * row, rather than joined: a join's order is the planner's (ThemePlanStabilityTest).
 */
internal const val CODE_LIST_COLUMNS: String =
    "t.id AS slug, t.tenant_key, " +
        "(SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id) AS catalog_key, " +
        "(SELECT c.namespace FROM catalogs c WHERE c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id) AS namespace, " +
        "t.display_name, t.description, t.source_type, t.source_url, t.auth_type, " +
        "(SELECT rc.credential FROM resource_credentials rc WHERE rc.tenant_key = t.tenant_key AND rc.credential_id = t.credential_id) AS credential, " +
        "(SELECT f.last_refreshed_at FROM code_list_refreshes f WHERE f.tenant_key = t.tenant_key AND f.version_id = t.version_id AND f.resource_id = t.resource_id) AS last_refreshed_at, " +
        "(SELECT f.last_refresh_error FROM code_list_refreshes f WHERE f.tenant_key = t.tenant_key AND f.version_id = t.version_id AND f.resource_id = t.resource_id) AS last_refresh_error, " +
        "t.created_at, t.updated_at, t.revision"

/** Code list [key] of [catalog] in `current_code_lists t`; binds `t`, `c` and `key`. */
internal fun codeListAt(catalogParam: String = "c", keyParam: String = "key"): String = "t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", catalogParam)} AND t.id = :$keyParam"

/** Where a code list's entries come from, beside its content (ADR 0033): `source` in its JSON. */
data class CodeListSourceSettings(
    val type: CodeListSource,
    val url: String?,
    val auth: AuthType,
    /** The credential it authenticates with, in `resource_credentials`; null for none. */
    val credentialId: UUID?,
)

/** What a code list says: everything its content holds but its key. */
data class CodeListFields(
    val name: String,
    val description: String?,
    val entries: List<CodeListEntry>,
    /** Null for an INLINE list, which has no source. */
    val source: CodeListSourceSettings?,
)

/**
 * Writes the code lists of a draft (ADR 0033): one content per version, `{ "resource": <its wire
 * form>, "source": {...} }`, stored by hash in `resource_contents`, and the draft's row naming it
 * ([DraftResources]). `resource` is what a release holds and exports; `source` is the suite's own
 * setting beside it, left out for an INLINE list. A credential is stored apart
 * ([storeCredential]) and named by id, so the secret is never part of what is hashed or exported.
 */
@Component
class CodeListStore(
    private val objectMapper: ObjectMapper,
    private val contents: ResourceContentStore,
) {

    /**
     * The content of code list [key]. Entries are stored in the order lists show them (sort order,
     * then code), so the same list stores the same, however its entries arrived.
     */
    fun payload(key: String, fields: CodeListFields): ObjectNode = objectMapper.createObjectNode().apply {
        set("resource", objectMapper.valueToTree<JsonNode>(resource(key, fields.name, fields.description, fields.entries)))
        fields.source?.let { set("source", sourceNode(it)) }
    }

    /** The source settings as content holds them. */
    fun sourceNode(source: CodeListSourceSettings): ObjectNode = objectMapper.createObjectNode()
        .put("type", source.type.name)
        .put("url", source.url)
        .put("auth", source.auth.name)
        .put("credential", source.credentialId?.toString())

    /** Stores [secret] encrypted, as a new credential, and returns its id. */
    fun storeCredential(handle: Handle, catalog: CatalogId, secret: String): UUID {
        val id = UUIDv7.generate()
        handle.createUpdate("INSERT INTO resource_credentials (tenant_key, credential_id, credential) VALUES (:t, :id, :credential)")
            .bind("t", catalog.tenantKey)
            .bind("id", id)
            .bind("credential", Secret(secret))
            .execute()
        return id
    }

    /** Stores [fields] as code list [key] of [catalog]'s tenant and returns its content hash. */
    fun store(handle: Handle, catalog: CatalogId, key: String, fields: CodeListFields): String = contents.store(handle, catalog.tenantKey, CODE_LIST, payload(key, fields))

    /** Adds code list [id] to the targeted draft; one already there is a unique violation. */
    fun create(handle: Handle, id: CodeListId, fields: CodeListFields, actor: UUID?): CodeList {
        DraftResources.insert(handle, id.catalogId, CODE_LIST, id.key.value, store(handle, id.catalogId, id.key.value, fields), fields.name, fields.description, actor)
        return checkNotNull(get(handle, id)) { "Code list ${id.key.value} was just written" }
    }

    /**
     * Saves [fields] as code list [id] of the targeted draft, when its revision is still
     * [expectedRevision] (null: whatever it is); null when the draft holds no such list, or it moved
     * on. Content that hashes the same writes nothing new and keeps the revision.
     */
    fun save(handle: Handle, id: CodeListId, fields: CodeListFields, actor: UUID?, expectedRevision: Long? = null): CodeList? {
        if (!write(handle, id, fields, actor, expectedRevision)) return null
        return get(handle, id)
    }

    /** As [save], without reading the list back; false when the draft holds no such list, or it moved on. */
    fun write(handle: Handle, id: CodeListId, fields: CodeListFields, actor: UUID?, expectedRevision: Long? = null): Boolean {
        val hash = store(handle, id.catalogId, id.key.value, fields)
        return DraftResources.update(handle, id.catalogId, CODE_LIST, id.key.value, hash, fields.name, fields.description, actor, expectedRevision) != null
    }

    /**
     * Creates or replaces code list [id] in the targeted draft, keeping the source a list already
     * there has (a received catalog brings none); true when it was there already.
     */
    fun put(handle: Handle, id: CodeListId, name: String, description: String?, entries: List<CodeListEntry>, actor: UUID?): Boolean {
        val existing = fields(handle, id)
        val fields = CodeListFields(name, description, entries, existing?.source)
        val hash = store(handle, id.catalogId, id.key.value, fields)
        if (DraftResources.update(handle, id.catalogId, CODE_LIST, id.key.value, hash, name, description, actor) != null) return true
        DraftResources.insert(handle, id.catalogId, CODE_LIST, id.key.value, hash, name, description, actor)
        return false
    }

    /** Removes code list [id] from the targeted draft; false when it held none. */
    fun delete(handle: Handle, id: CodeListId): Boolean = DraftResources.delete(handle, id.catalogId, CODE_LIST, id.key.value)

    /** Moves code list [key] of [source] to [targetKey] of [target], with its content renamed; false when there is none. */
    fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean {
        val fields = fields(handle, CodeListId(app.epistola.suite.common.ids.CodeListKey.of(key), source)) ?: return false
        return DraftResources.move(handle, source, CODE_LIST, key, target, targetKey, store(handle, source, targetKey, fields))
    }

    /** Code list [id] as the version the handle means holds it, or null. */
    fun get(handle: Handle, id: CodeListId): CodeList? = handle
        .createQuery("SELECT $CODE_LIST_COLUMNS FROM current_code_lists t WHERE ${codeListAt()}")
        .bind("t", id.tenantKey)
        .bindCatalog("c", id.catalogId)
        .bind("key", id.key.value)
        .mapTo<CodeList>()
        .findOne()
        .orElse(null)

    /** What code list [id] says, read from its content; null when the version holds none. */
    fun fields(handle: Handle, id: CodeListId): CodeListFields? = handle
        .createQuery("SELECT t.payload::text FROM current_code_lists t WHERE ${codeListAt()}")
        .bind("t", id.tenantKey)
        .bindCatalog("c", id.catalogId)
        .bind("key", id.key.value)
        .mapTo(String::class.java)
        .findOne()
        .orElse(null)
        ?.let { fieldsOf(objectMapper.readTree(it)) }

    /** The settings beside the released form of each of [catalog]'s code lists that has a source, by key. */
    fun sources(handle: Handle, catalog: CatalogId): Map<String, JsonNode> = handle
        .createQuery("SELECT t.id, (t.payload -> 'source')::text AS source FROM current_code_lists t WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")} AND t.payload -> 'source' IS NOT NULL")
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .map { rs, _ -> rs.getString("id") to objectMapper.readTree(rs.getString("source")) }
        .list()
        .toMap()

    /** Records a refresh of code list [id] in the targeted draft: when it succeeded (null: it did not), and its error. */
    fun recordRefresh(handle: Handle, id: CodeListId, succeeded: Boolean, error: String?) {
        handle.createUpdate(
            """
            INSERT INTO code_list_refreshes (tenant_key, version_id, resource_id, last_refreshed_at, last_refresh_error)
            SELECT t.tenant_key, t.version_id, t.resource_id, CASE WHEN :succeeded THEN NOW() END, :error
            FROM current_code_lists t WHERE ${codeListAt()} AND t.version_id IS NOT NULL
            ON CONFLICT (tenant_key, version_id, resource_id) DO UPDATE
            SET last_refreshed_at = CASE WHEN :succeeded THEN NOW() ELSE code_list_refreshes.last_refreshed_at END,
                last_refresh_error = :error
            """,
        )
            .bind("t", id.tenantKey)
            .bindCatalog("c", id.catalogId)
            .bind("key", id.key.value)
            .bind("succeeded", succeeded)
            .bind("error", error)
            .execute()
    }

    /** What a stored content says. */
    fun fieldsOf(payload: JsonNode): CodeListFields {
        val resource = StoredContent.resourceOf(payload)
        val source = StoredContent.settingOf(payload, "source")?.takeIf { it.isObject }?.let { node ->
            CodeListSourceSettings(
                type = CodeListSource.valueOf(node.path("type").asString()),
                url = node.get("url")?.takeUnless { it.isNull }?.asString(),
                auth = AuthType.valueOf(node.path("auth").asString("NONE")),
                credentialId = node.get("credential")?.takeUnless { it.isNull }?.asString()?.let(UUID::fromString),
            )
        }
        return CodeListFields(
            name = resource.path("name").asString(),
            description = resource.get("description")?.takeUnless { it.isNull }?.asString(),
            entries = resource.path("entries").toList().map { e ->
                CodeListEntry(e.path("code").asString(), e.path("label").asString(), e.path("sortOrder").asInt(), e.path("hidden").asBoolean())
            },
            source = source,
        )
    }

    companion object {
        /** The resource type, and content kind, of a code list. */
        const val CODE_LIST = "codeList"

        /** The released form of a code list (`CodeListResource`), its entries in the order lists show them. */
        fun resource(key: String, name: String, description: String?, entries: List<CodeListEntry>): CodeListResource = CodeListResource(
            key = key,
            name = name,
            description = description,
            entries = entries.sortedWith(compareBy({ it.sortOrder }, { it.code })).map { CodeListEntryEntry(it.code, it.label, it.sortOrder, it.hidden) },
        )
    }
}
