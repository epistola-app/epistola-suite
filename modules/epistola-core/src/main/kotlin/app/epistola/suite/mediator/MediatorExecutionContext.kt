package app.epistola.suite.mediator

import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.time.EpistolaClock
import java.time.Clock
import java.util.concurrent.Callable

internal data class MediatorExecutionContext(
    val mediator: Mediator,
    val clock: Clock = Clock.systemUTC(),
    val principal: EpistolaPrincipal? = null,
) {
    fun <T> bind(block: () -> T): T = MediatorContext.runWithContext(this, block)

    fun runnable(block: () -> Unit): Runnable = Runnable { bind(block) }

    fun <T> callable(block: () -> T): Callable<T> = Callable { bind(block) }

    companion object {
        fun capture(
            mediator: Mediator,
            principal: EpistolaPrincipal? = SecurityContext.currentOrNull(),
        ): MediatorExecutionContext {
            val context = if (MediatorContext.isBound()) {
                MediatorContext.capture()
            } else {
                MediatorExecutionContext(
                    mediator = mediator,
                    clock = EpistolaClock.capture(),
                    principal = SecurityContext.currentOrNull(),
                )
            }
            return context.copy(principal = principal)
        }
    }
}
