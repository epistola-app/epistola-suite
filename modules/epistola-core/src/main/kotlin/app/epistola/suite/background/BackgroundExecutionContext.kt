package app.epistola.suite.background

import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.time.EpistolaClock
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.Callable

@Component
class BackgroundExecutionContext(
    private val mediator: Mediator,
) {
    fun <T> run(block: () -> T): T = runWithClock(EpistolaClock.capture()) {
        MediatorContext.runWithMediator(mediator, block)
    }

    fun <T> runAs(
        principal: EpistolaPrincipal,
        block: () -> T,
    ): T = run {
        SecurityContext.runWithPrincipal(principal, block)
    }

    fun wrap(block: () -> Unit): Runnable {
        val clock = EpistolaClock.capture()
        return Runnable {
            runWithClock(clock) {
                MediatorContext.runWithMediator(mediator, block)
            }
        }
    }

    fun <T> wrapCallable(block: () -> T): Callable<T> {
        val clock = EpistolaClock.capture()
        return Callable {
            runWithClock(clock) {
                MediatorContext.runWithMediator(mediator, block)
            }
        }
    }

    fun wrapAs(
        principal: EpistolaPrincipal,
        block: () -> Unit,
    ): Runnable {
        val clock = EpistolaClock.capture()
        return Runnable {
            runWithClock(clock) {
                MediatorContext.runWithMediator(mediator) {
                    SecurityContext.runWithPrincipal(principal, block)
                }
            }
        }
    }

    private fun <T> runWithClock(
        clock: Clock,
        block: () -> T,
    ): T = EpistolaClock.withClock(clock, block)
}
