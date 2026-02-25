package app.epistola.suite.mediator

/**
 * Marks commands that can be routed to specific instances in a distributed system.
 *
 * Commands with the same routing key are guaranteed to be processed by the same instance.
 * This enables:
 * - Sharding by tenant (all operations for a tenant go to the same instance)
 * - Ordered processing within a partition
 * - Stateful handlers without coordination
 *
 * For most tenant-scoped commands, use tenant ID as the routing key:
 * ```kotlin
 * data class CreateTheme(...) : Command<Theme>, TenantScoped, Routable {
 *     override val routingKey: String get() = tenantId.value
 * }
 * ```
 *
 * FUTURE: This interface is designed for horizontal scaling.
 * Currently, all commands execute locally, and routing keys are logged for future use.
 * When distribution is implemented:
 * 1. Add pending_commands table (similar to document_generation_requests)
 * 2. Implement CommandRouter that routes by key
 * 3. Add consumer position tracking for event subscribers
 */
interface Routable {
    /**
     * String key used for routing. Commands with the same key are processed by the same instance.
     * Typically the tenant ID for tenant-scoped operations.
     */
    val routingKey: String
}
