package app.epistola.suite.mediator

import java.time.Clock

data class MediatorExecutionContext(
    val mediator: Mediator,
    val clock: Clock = Clock.systemUTC(),
)
