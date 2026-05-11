package app.epistola.suite.attributes.model

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListKey
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
 * When bound to a code list, `codeListCatalogKey` identifies the catalog that
 * contains the code list within the same tenant. A composite FK on
 * `(tenant_key, code_list_catalog_key, code_list_slug)` enforces that the
 * code list exists and lives under the same tenant.
 */
data class VariantAttributeDefinition(
    val id: AttributeKey,
    val tenantKey: TenantKey,
    @Suppress("DEPRECATION") val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val catalogType: CatalogType = CatalogType.AUTHORED,
    val displayName: String,
    @Json val allowedValues: List<String> = emptyList(),
    val codeListCatalogKey: CatalogKey? = null,
    val codeListSlug: CodeListKey? = null,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
