// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.mapper.RowMapper
import org.jdbi.v3.core.statement.StatementContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The columns a theme read selects from `current_themes` aliased `t`, with its catalog's key and
 * namespace (join `catalogs c`). Every theme read selects these and maps them with [ThemeRows].
 */
internal const val THEME_COLUMNS: String =
    "t.tenant_key, t.catalog_id, t.id, t.payload::text AS payload, t.created_at, t.updated_at, t.created_by, t.updated_by, t.revision, " +
        "c.catalog_key AS catalog_key, c.namespace AS namespace"

/** `current_themes t JOIN catalogs c`: the themes of the versions callers mean, with their catalogs. */
internal const val THEMES_WITH_CATALOG: String =
    "current_themes t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id"

/**
 * The one mapping from a theme's JSON content (its wire form, ADR 0033) to the [Theme] the suite
 * reads, for a draft and a release alike.
 */
object ThemeContent {
    /** The theme [payload] holds, with the row's bookkeeping. */
    fun theme(
        objectMapper: ObjectMapper,
        payload: JsonNode,
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        namespace: String,
        createdAt: OffsetDateTime,
        updatedAt: OffsetDateTime,
        createdBy: UserKey?,
        updatedBy: UserKey?,
        revision: Long,
    ): Theme = Theme(
        id = ThemeKey.of(payload.path("key").asString()),
        tenantKey = tenantKey,
        catalogKey = catalogKey,
        name = payload.path("name").asString(),
        description = payload.text("description"),
        documentStyles = payload.value("documentStyles")?.let { objectMapper.treeToValue(it, DocumentStyles::class.java) } ?: emptyMap(),
        pageSettings = payload.value("pageSettings")?.let { objectMapper.treeToValue(it, PageSettings::class.java) },
        blockStylePresets = payload.value("blockStylePresets")?.let { objectMapper.treeToValue(it, BlockStylePresets::class.java) },
        spacingUnit = payload.value("spacingUnit")?.floatValue(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        createdBy = createdBy,
        updatedBy = updatedBy,
        revision = revision,
        namespace = namespace,
    )

    private fun JsonNode.value(field: String): JsonNode? = get(field)?.takeUnless { it.isNull || it.isMissingNode }

    private fun JsonNode.text(field: String): String? = value(field)?.asString()
}

/** Maps a row of [THEME_COLUMNS] to a [Theme] through [ThemeContent]. */
class ThemeRows(private val objectMapper: ObjectMapper) : RowMapper<Theme> {
    override fun map(rs: ResultSet, ctx: StatementContext): Theme = ThemeContent.theme(
        objectMapper,
        objectMapper.readTree(rs.getString("payload")),
        TenantKey.of(rs.getString("tenant_key")),
        CatalogKey.of(rs.getString("catalog_key")),
        rs.getString("namespace"),
        rs.getObject("created_at", OffsetDateTime::class.java),
        rs.getObject("updated_at", OffsetDateTime::class.java),
        rs.getObject("created_by", UUID::class.java)?.let { UserKey.of(it) },
        rs.getObject("updated_by", UUID::class.java)?.let { UserKey.of(it) },
        rs.getLong("revision"),
    )
}

/**
 * Theme reads on a handle, through `current_themes`: of the version the handle's transaction means
 * (the message's target, or what [app.epistola.suite.catalog.releases.VersionReads] selects).
 */
object ThemeReads {
    /** Theme [key] of [catalog], or null. */
    fun get(handle: Handle, objectMapper: ObjectMapper, catalog: CatalogId, key: ThemeKey): Theme? = handle
        .createQuery("SELECT $THEME_COLUMNS FROM $THEMES_WITH_CATALOG WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")} AND t.id = :id")
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("id", key.value)
        .map(ThemeRows(objectMapper))
        .findOne()
        .orElse(null)
}
