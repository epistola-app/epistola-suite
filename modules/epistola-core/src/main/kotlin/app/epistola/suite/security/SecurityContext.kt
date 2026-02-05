package app.epistola.suite.security

/**
 * Provides thread-scoped access to the authenticated user using ScopedValue.
 *
 * ScopedValue is preferred over ThreadLocal for virtual threads because:
 * - Immutable within scope (cannot be accidentally modified)
 * - Automatic cleanup when scope ends
 * - Designed for virtual thread compatibility (JDK 25)
 *
 * Consistent with MediatorContext pattern used elsewhere in the codebase.
 *
 * Usage in production:
 * - The SecurityFilter (apps/epistola) binds the principal for each HTTP request
 * - Use `SecurityContext.current()` to access the bound principal
 * - Use `SecurityContext.currentOrNull()` to check if authenticated
 *
 * Usage in tests:
 * - Use `SecurityContext.runWithPrincipal(principal) { ... }` to explicitly bind scope
 */
object SecurityContext {
    private val scopedPrincipal: ScopedValue<EpistolaPrincipal> = ScopedValue.newInstance()

    /**
     * Returns the currently authenticated user.
     *
     * @throws IllegalStateException if no user is authenticated in the current scope
     */
    fun current(): EpistolaPrincipal = scopedPrincipal.orElseThrow {
        IllegalStateException(
            "No authenticated user in current scope. " +
                "Ensure code runs within SecurityFilter (HTTP requests) " +
                "or SecurityContext.runWithPrincipal() (tests/background tasks).",
        )
    }

    /**
     * Returns the currently authenticated user, or null if not authenticated.
     *
     * Use this for optional authentication checks.
     */
    fun currentOrNull(): EpistolaPrincipal? = if (scopedPrincipal.isBound) scopedPrincipal.get() else null

    /**
     * Runs the given block with the specified principal bound to the current scope.
     *
     * This is primarily used for:
     * - Tests that need explicit authentication context
     * - Background tasks that run outside of HTTP request context
     * - Command/query handlers that need to execute as a specific user
     *
     * @param principal the authenticated user to bind for the duration of the block
     * @param block the code to execute with the principal bound
     * @return the result of executing the block
     */
    fun <T> runWithPrincipal(
        principal: EpistolaPrincipal,
        block: () -> T,
    ): T = ScopedValue.where(scopedPrincipal, principal).call<T, RuntimeException>(block)

    /**
     * Checks if a principal is currently bound to the scope.
     */
    fun isBound(): Boolean = scopedPrincipal.isBound
}
