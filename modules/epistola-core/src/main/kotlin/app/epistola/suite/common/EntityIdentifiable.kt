package app.epistola.suite.common

/**
 * Marker interface for commands and messages that carry a primary entity ID.
 *
 * This enables:
 * - Generic entity ID extraction for event logging
 * - Linking events to specific entities in audit trails
 * - Entity-level event querying and replay
 *
 * Implement on commands that want their primary entity ID recorded in the event log:
 * ```kotlin
 * data class CreateTheme(
 *     val id: ThemeId,
 *     override val tenantId: TenantId,
 * ) : Command<Theme>, TenantScoped, EntityIdentifiable {
 *     override val entityId: String get() = id.value
 * }
 * ```
 *
 * For commands that operate on a single entity without a separate `id` field,
 * return the primary identifier directly:
 * ```kotlin
 * data class DeleteTheme(
 *     val id: ThemeId,
 *     override val tenantId: TenantId,
 * ) : Command<Unit>, TenantScoped, EntityIdentifiable {
 *     override val entityId: String get() = id.value
 * }
 * ```
 */
interface EntityIdentifiable {
    /**
     * The primary ID of the entity being operated on by this message.
     */
    val entityId: String
}
