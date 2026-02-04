package app.epistola.suite.mediator

/**
 * Provides thread-scoped access to the Mediator instance using ScopedValue.
 *
 * ScopedValue is preferred over ThreadLocal for virtual threads because:
 * - Immutable within scope (cannot be accidentally modified)
 * - Automatic cleanup when scope ends
 * - Designed for virtual thread compatibility (JDK 25)
 *
 * Usage in production:
 * - The MediatorFilter binds the mediator for each HTTP request
 * - Use `MediatorContext.current()` to access the bound mediator
 *
 * Usage in tests:
 * - Use `MediatorContext.runWithMediator(mediator) { ... }` to explicitly bind scope
 */
object MediatorContext {
    private val scopedMediator: ScopedValue<Mediator> = ScopedValue.newInstance()

    /**
     * Returns the currently bound Mediator instance.
     *
     * @throws IllegalStateException if no mediator is bound in the current scope
     */
    fun current(): Mediator = scopedMediator.orElseThrow {
        IllegalStateException(
            "No Mediator bound to current scope. " +
                "Ensure code is running within MediatorFilter (HTTP requests) " +
                "or MediatorContext.runWithMediator() (tests/background tasks).",
        )
    }

    /**
     * Runs the given block with the specified mediator bound to the current scope.
     *
     * This is primarily used for:
     * - Tests that need explicit mediator scope control
     * - Background tasks that run outside of HTTP request context
     *
     * @param mediator the mediator to bind for the duration of the block
     * @param block the code to execute with the mediator bound
     * @return the result of executing the block
     */
    fun <T> runWithMediator(
        mediator: Mediator,
        block: () -> T,
    ): T = ScopedValue.where(scopedMediator, mediator).call<T, RuntimeException>(block)

    /**
     * Checks if a mediator is currently bound to the scope.
     */
    fun isBound(): Boolean = scopedMediator.isBound
}
