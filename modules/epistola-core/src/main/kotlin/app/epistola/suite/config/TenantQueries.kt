package app.epistola.suite.config

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo

/**
 * Kotlin extension functions for tenant-scoped queries.
 *
 * These provide type-safe wrappers around common patterns:
 * - Finding by tenant + ID
 * - Listing all for a tenant
 * - Checking existence
 * - Deleting
 *
 * All functions follow the pattern of:
 * 1. Accepting table name as string (SQL stays explicit, not hidden in DSL)
 * 2. Type inference via reified type parameter
 * 3. Tenant scoping baked in
 *
 * Before:
 * ```kotlin
 * override fun handle(query: GetTheme): Theme? = jdbi.withHandle<Theme?, Exception> { handle ->
 *     handle.createQuery(
 *         "SELECT * FROM themes WHERE id = :id AND tenant_id = :tenantId"
 *     )
 *         .bind("id", query.id)
 *         .bind("tenantId", query.tenantId)
 *         .mapTo<Theme>()
 *         .findOne()
 *         .orElse(null)
 * }
 * ```
 *
 * After:
 * ```kotlin
 * override fun handle(query: GetTheme): Theme? = jdbi.withHandle { handle ->
 *     handle.findByTenantAndId<Theme>("themes", query.tenantId, query.id)
 * }
 * ```
 */

/**
 * Find a single tenant-scoped entity by ID.
 *
 * Assumes table has `id` and `tenant_id` columns.
 *
 * @param T The entity type to map to
 * @param table The table name (e.g., "themes", "templates")
 * @param tenantId The tenant to scope to
 * @param id The entity ID to find
 * @param columns Columns to select (default: "*")
 * @return The entity if found, null otherwise
 */
inline fun <reified T : Any> Handle.findByTenantAndId(
    table: String,
    tenantId: TenantKey,
    id: Any,
    columns: String = "*",
): T? = createQuery("SELECT $columns FROM $table WHERE tenant_id = :tenantId AND id = :id")
    .bind("tenantId", tenantId.value)
    .bind("id", id)
    .mapTo<T>()
    .findOne()
    .orElse(null)

/**
 * Find a single root entity by ID (no tenant scoping).
 *
 * Use this for root-level entities that are NOT tenant-scoped
 * (e.g., tenants, api_keys when queried by tenant).
 *
 * @param T The entity type to map to
 * @param table The table name
 * @param id The entity ID to find
 * @param columns Columns to select (default: "*")
 * @return The entity if found, null otherwise
 */
inline fun <reified T : Any> Handle.findById(
    table: String,
    id: Any,
    columns: String = "*",
): T? = createQuery("SELECT $columns FROM $table WHERE id = :id")
    .bind("id", id)
    .mapTo<T>()
    .findOne()
    .orElse(null)

/**
 * List all entities for a tenant.
 *
 * @param T The entity type to map to
 * @param table The table name
 * @param tenantId The tenant to scope to
 * @param orderBy Order clause (default: "created_at DESC")
 * @return List of entities, ordered as specified
 */
inline fun <reified T : Any> Handle.listForTenant(
    table: String,
    tenantId: TenantKey,
    orderBy: String = "created_at DESC",
): List<T> = createQuery(
    "SELECT * FROM $table WHERE tenant_id = :tenantId ORDER BY $orderBy",
)
    .bind("tenantId", tenantId.value)
    .mapTo<T>()
    .list()

/**
 * Check if a tenant-scoped entity exists.
 *
 * @param table The table name
 * @param tenantId The tenant to scope to
 * @param id The entity ID to check
 * @return True if entity exists, false otherwise
 */
fun Handle.existsForTenant(table: String, tenantId: TenantKey, id: Any): Boolean = createQuery(
    "SELECT EXISTS(SELECT 1 FROM $table WHERE tenant_id = :tenantId AND id = :id)",
)
    .bind("tenantId", tenantId.value)
    .bind("id", id)
    .mapTo<Boolean>()
    .one()

/**
 * Delete a tenant-scoped entity by ID.
 *
 * @param table The table name
 * @param tenantId The tenant to scope to
 * @param id The entity ID to delete
 * @return True if a row was deleted, false if not found
 */
fun Handle.deleteForTenant(table: String, tenantId: TenantKey, id: Any): Boolean = createUpdate("DELETE FROM $table WHERE tenant_id = :tenantId AND id = :id")
    .bind("tenantId", tenantId.value)
    .bind("id", id)
    .execute() > 0

/**
 * Count entities for a tenant.
 *
 * @param table The table name
 * @param tenantId The tenant to scope to
 * @return Number of entities for this tenant
 */
fun Handle.countForTenant(table: String, tenantId: TenantKey): Int = createQuery("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenantId")
    .bind("tenantId", tenantId.value)
    .mapTo<Int>()
    .one()
