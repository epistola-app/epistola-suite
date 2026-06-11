package app.epistola.suite.mediator

import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.time.EpistolaClock
import java.time.Clock
import java.util.concurrent.Callable

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
    private val scopedContext: ScopedValue<MediatorExecutionContext> = ScopedValue.newInstance()

    /**
     * Returns the currently bound Mediator instance.
     *
     * @throws IllegalStateException if no mediator is bound in the current scope
     */
    fun current(): Mediator = currentExecutionContext().mediator

    private fun currentExecutionContext(): MediatorExecutionContext = scopedContext.orElseThrow {
        IllegalStateException(
            "No Mediator bound to current scope. " +
                "Ensure code is running within MediatorFilter (HTTP requests) " +
                "or MediatorContext.runWithMediator() (tests/background tasks).",
        )
    }

    fun currentClockOrNull(): Clock? = if (scopedContext.isBound) scopedContext.get().clock else null

    fun currentClock(): Clock = currentExecutionContext().clock

    internal fun capture(): MediatorExecutionContext {
        val context = currentExecutionContext()
        return context.copy(
            clock = EpistolaClock.capture(),
            principal = SecurityContext.currentOrNull(),
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
    ): T = runWithContext(MediatorExecutionContext(mediator = mediator, clock = EpistolaClock.capture()), block)

    fun runnable(
        mediator: Mediator,
        principal: EpistolaPrincipal? = SecurityContext.currentOrNull(),
        block: () -> Unit,
    ): Runnable = MediatorExecutionContext.capture(mediator, principal).runnable(block)

    fun <T> callable(
        mediator: Mediator,
        principal: EpistolaPrincipal? = SecurityContext.currentOrNull(),
        block: () -> T,
    ): Callable<T> = MediatorExecutionContext.capture(mediator, principal).callable(block)

    internal fun <T> runWithContext(
        context: MediatorExecutionContext,
        block: () -> T,
    ): T = ScopedValue.where(scopedContext, context).call<T, RuntimeException> {
        val principal = context.principal
        if (principal == null) {
            block()
        } else {
            SecurityContext.runWithPrincipal(principal, block)
        }
    }

    /**
     * Checks if a mediator is currently bound to the scope.
     */
    fun isBound(): Boolean = scopedContext.isBound
}
