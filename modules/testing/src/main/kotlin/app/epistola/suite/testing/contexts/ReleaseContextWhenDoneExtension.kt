// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.contexts

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.core.Ordered
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Closes a test class's Spring context once it was the last class of the run to need it
 * ([ContextUsage]), instead of keeping it in the heap until the JVM exits.
 *
 * It must run after every other `afterAll` that still reads the context, such as the catalog
 * invariants check. JUnit calls `afterAll` callbacks in the reverse order the extensions were
 * registered, so this one is registered first, on `IntegrationTestBase`. That is also why the close
 * is not in a Spring `TestExecutionListener`: those run inside `SpringExtension`, which a subclass's
 * `@SpringBootTest` registers later, so its `afterAll` runs before the invariants check.
 */
class ReleaseContextWhenDoneExtension : AfterAllCallback {
    override fun afterAll(context: ExtensionContext) {
        val testClass = context.requiredTestClass
        val last = ContextUsage.run.finished(testClass)
        val testContext = TestContextHandoff.take(testClass)
        if (last && testContext != null) testContext.markApplicationContextDirty(null)
    }
}

/**
 * Hands [ReleaseContextWhenDoneExtension] the Spring [TestContext] of each test class, which
 * `SpringExtension` does not expose. Registered through `META-INF/spring.factories`.
 */
class TestContextHandoff :
    TestExecutionListener,
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun beforeTestClass(testContext: TestContext) {
        contexts[testContext.testClass] = testContext
    }

    internal companion object {
        private val contexts = ConcurrentHashMap<Class<*>, TestContext>()

        fun take(testClass: Class<*>): TestContext? = contexts.remove(testClass)
    }
}
