// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications

import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.TenantRole
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

/**
 * A calling system in one tenant (ADR 0054): it owns its API keys and the documents it requested.
 *
 * @property roles The tenant roles the application acts with; each of its keys carries a copy.
 * @property seesAllDocuments True: sees every document of the tenant, as callers did before
 *   applications existed. False: only the documents it requested.
 * @property activeKeyCount Keys that are not revoked; set by the listing queries.
 */
data class Application(
    val id: ApplicationKey,
    val tenantKey: TenantKey,
    val name: String,
    val description: String?,
    val roles: Set<TenantRole>,
    val seesAllDocuments: Boolean,
    val createdAt: Instant,
    val createdBy: UserKey?,
    val createdByDisplayName: String? = null,
    val activeKeyCount: Int = 0,
)

/** Matches `applications.description`. */
const val MAX_APPLICATION_DESCRIPTION_LENGTH = 500

/** The columns every application query selects, aliased from `applications a`. */
internal const val APPLICATION_COLUMNS = """
    a.id, a.tenant_key, a.name, a.description, a.roles, a.sees_all_documents, a.created_at, a.created_by,
    COALESCE(u.display_name, u.email) AS created_by_display_name,
    (SELECT COUNT(*) FROM api_keys k WHERE k.application_id = a.id AND k.revoked_at IS NULL) AS active_key_count
"""

/** The joins [APPLICATION_COLUMNS] needs. */
internal const val APPLICATION_FROM = """
    FROM applications a
    LEFT JOIN users u ON u.id = a.created_by
"""

internal fun ResultSet.toApplication(): Application = Application(
    id = ApplicationKey.of(getString("id")),
    tenantKey = TenantKey.of(getString("tenant_key")),
    name = getString("name"),
    description = getString("description"),
    roles = tenantRolesFromSqlArray(getArray("roles")),
    seesAllDocuments = getBoolean("sees_all_documents"),
    createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
    createdBy = getString("created_by")?.let { UserKey.of(it) },
    createdByDisplayName = getString("created_by_display_name"),
    activeKeyCount = getInt("active_key_count"),
)

/** Decodes a `VARCHAR[]` of role names; names no longer known are dropped. */
fun tenantRolesFromSqlArray(array: java.sql.Array?): Set<TenantRole> = ((array?.array as? Array<*>)?.toList() ?: emptyList())
    .filterIsInstance<String>()
    .mapNotNull { runCatching { TenantRole.valueOf(it) }.getOrNull() }
    .toSet()
