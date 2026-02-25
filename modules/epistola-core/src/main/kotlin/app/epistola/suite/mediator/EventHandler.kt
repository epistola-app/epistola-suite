package app.epistola.suite.mediator

/**
 * Typed event subscription for a specific command type.
 *
 * Implement this interface and mark your class with @Component to subscribe to events.
 * The mediator will automatically discover all EventHandler beans and invoke them after
 * a matching command completes.
 *
 * Example:
 * ```kotlin
 * @Component
 * class OnThemeCreated : EventHandler<CreateTheme> {
 *     override fun on(event: CreateTheme, result: Any?) {
 *         val theme = result as Theme
 *         invalidateCache(theme.id)
 *     }
 * }
 * ```
 *
 * @param C The command type this handler subscribes to
 */
interface EventHandler<C : Command<*>> {
    /**
     * When this handler should be invoked.
     * Default is AFTER_COMMIT (safe for most use cases).
     */
    val phase: EventPhase
        get() = EventPhase.AFTER_COMMIT

    /**
     * Invoked after the command completes successfully.
     *
     * @param event The command that was executed
     * @param result The result returned by the command handler
     * @throws Exception Exceptions are handled based on [phase]:
     *     - IMMEDIATE: propagates to caller (can roll back)
     *     - AFTER_COMMIT: logged and suppressed
     */
    fun on(event: C, result: Any?)
}
