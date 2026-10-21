// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.templates.model.TemplateDocument
import org.jdbi.v3.json.Json
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime

/**
 * Stencil entity — a reusable template component, with its working copy.
 *
 * A stencil has no versions of its own: it is versioned with its catalog (contract ADR 0002), and
 * templates use it by reference (ADR 0006).
 */
data class Stencil(
    val id: StencilKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val name: String,
    val description: String? = null,
    @Json val tags: List<String> = emptyList(),
    @Json val content: TemplateDocument,
    /** JSON Schema of the parameters an instance binds; null when it takes none. */
    @Json val parameterSchema: JsonNode? = null,
    /** Its content also sees the using template's data (ADR 0006, part 5). */
    val readsTemplateData: Boolean = false,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val createdBy: UserKey? = null,
    val updatedBy: UserKey? = null,
    /** Raised by every change; see [app.epistola.suite.common.Revisions]. */
    val revision: Long = 1,
)

/**
 * Whether [schema] declares no parameters: `{}`, or an object schema without properties. A stencil
 * stores that as no schema, so "no parameters" has one form, and an update sends it to remove them
 * (leaving the schema out keeps it).
 */
fun declaresNoParameters(schema: JsonNode?): Boolean {
    if (schema == null || !schema.isObject) return false
    val properties = schema.get("properties")
    val required = schema.get("required")
    return (properties == null || (properties.isObject && properties.size() == 0)) &&
        (required == null || (required.isArray && required.size() == 0))
}

/**
 * This stencil with an absent parameter schema as null. JDBI hands a SQL NULL JSON column to Jackson
 * as the text `null`, which Jackson reads into a [JsonNode] as a `NullNode`, not as null.
 */
internal fun Stencil.withAbsentSchemaAsNull(): Stencil = if (parameterSchema?.isNull == true) copy(parameterSchema = null) else this

/**
 * The columns [Stencil] maps from, for a query over `stencils s JOIN catalogs c` ([STENCIL_FROM]).
 */
internal const val STENCIL_COLUMNS: String =
    "s.id, s.tenant_key, c.catalog_key AS catalog_key, s.name, s.description, s.tags, " +
        "s.content, s.parameter_schema, s.reads_template_data, s.created_at, s.updated_at, s.created_by, s.updated_by, s.revision"

/** Stencils with their catalog, each in its catalog's current version (`catalog_current_versions`). */
internal const val STENCIL_FROM: String = """FROM stencils s JOIN catalogs c ON c.tenant_key = s.tenant_key AND c.catalog_id = s.catalog_id
    JOIN catalog_current_versions scv ON scv.tenant_key = s.tenant_key AND scv.version_id = s.version_id"""
