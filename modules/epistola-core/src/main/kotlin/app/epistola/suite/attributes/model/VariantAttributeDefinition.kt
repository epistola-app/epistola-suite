package app.epistola.suite.attributes.model

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

/**
 * Definition of an attribute that can constrain template variant values.
 *
 * An attribute is constrained in exactly one of three ways:
 *  1. Free format        — `allowedValues` is empty AND `codeListSlug` is null
 *  2. Inline values      — `allowedValues` is non-empty AND `codeListSlug` is null
 *  3. Bound to code list — `codeListSlug` is non-null AND `allowedValues` is empty
 *
 * The DB enforces (2) and (3) are mutually exclusive via `attr_constraint_kind_xor`.
 *
 * `codeListCatalogKey` + `codeListSlug` are the storage shape (JDBI maps the
 * columns one-to-one). Use [codeListId] to get a typed `CodeListId?` aggregate
 * — it's null exactly when the attribute is not bound to a code list.
 *
 * A composite FK on `(tenant_key, code_list_catalog_key, code_list_slug)`
 * enforces that the bound code list exists and lives under the same tenant.
 */
data class VariantAttributeDefinition(
    val id: AttributeKey,
    val tenantKey: TenantKey,
    @Suppress("DEPRECATION") val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    @get:org.jdbi.v3.core.mapper.reflect.ColumnName("catalog_type")
    val catalogType: CatalogType = CatalogType.AUTHORED,
    val displayName: String,
    @Json val allowedValues: List<String> = emptyList(),
    val codeListCatalogKey: CatalogKey? = null,
    val codeListSlug: CodeListKey? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    /**
     * The bound code list as a typed `CodeListId`, or `null` when this
     * attribute uses free-format / inline values. The DB-level `CHECK`
     * constraint guarantees the catalog key and slug columns are set
     * together, so this getter only succeeds when both are non-null.
     */
    val codeListId: CodeListId?
        get() {
            val catalog = codeListCatalogKey ?: return null
            val slug = codeListSlug ?: return null
            return CodeListId(slug, CatalogId(catalog, TenantId(tenantKey)))
        }
}
