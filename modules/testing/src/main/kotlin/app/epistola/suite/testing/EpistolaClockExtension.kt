// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.time.EpistolaClock
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import java.lang.reflect.Method

class EpistolaClockExtension : InvocationInterceptor {
    override fun interceptBeforeEachMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) = withMutableClock(extensionContext) { invocation.proceed() }

    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) = withMutableClock(extensionContext) { invocation.proceed() }

    override fun interceptTestTemplateMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) = withMutableClock(extensionContext) { invocation.proceed() }

    override fun interceptAfterEachMethod(
        invocation: InvocationInterceptor.Invocation<Void?>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) = withMutableClock(extensionContext) { invocation.proceed() }

    private fun withMutableClock(
        extensionContext: ExtensionContext,
        block: () -> Unit,
    ) {
        val previous = currentClock.get()
        val clock = clockFor(extensionContext)
        currentClock.set(clock)
        var failure: Throwable? = null
        try {
            EpistolaClock.withClock(clock) {
                try {
                    block()
                } catch (t: Throwable) {
                    failure = t
                }
            }
        } finally {
            if (previous == null) {
                currentClock.remove()
            } else {
                currentClock.set(previous)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        private val namespace = ExtensionContext.Namespace.create(EpistolaClockExtension::class.java)
        private val currentClock = ThreadLocal<MutableClock>()

        fun current(): MutableClock = currentClock.get()
            ?: error("No Epistola test clock is bound to the current test invocation")

        private fun clockFor(extensionContext: ExtensionContext): MutableClock {
            val store = extensionContext.getStore(namespace)
            val existing = store.get("clock", MutableClock::class.java)
            if (existing != null) {
                return existing
            }
            val clock = MutableClock()
            store.put("clock", clock)
            return clock
        }
    }
}
