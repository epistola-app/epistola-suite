// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.invariants

import app.epistola.suite.testing.TestTenantCounter
import app.epistola.suite.testing.metrics.TestRunMetrics
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.commons.support.AnnotationSupport
import org.slf4j.LoggerFactory
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.nio.file.Files
import java.nio.file.Path

/**
 * A test class, or one test, that leaves its tenants' catalogs inconsistent on purpose (a guard test
 * writing a broken row to see it refused or reported) opts out of [CatalogInvariantsExtension],
 * saying why. On a test, only the tenants that test creates are left out.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SkipCatalogInvariants(val reason: String)

/**
 * After a test class, checks [CatalogInvariants] over the tenants it created, so every integration
 * test also tests that the commands it ran left the catalogs consistent. Once per top-level class and
 * only when it created a tenant: one query over that class's tenants, a few milliseconds.
 *
 * `-PtestInvariants` (system property `epistola.test.invariants`) sets the mode: `fail` (the
 * default), `report` (write the violations to `build/invariant-violations/` and pass, to survey a
 * suite) or `off`. `EPISTOLA_TEST_INVARIANTS` stands in for it when a test is started from the IDE.
 */
class CatalogInvariantsExtension :
    BeforeEachCallback,
    AfterEachCallback,
    AfterAllCallback {
    // Which test creates a tenant, so a violation names the test that left it.
    override fun beforeEach(context: ExtensionContext) = TestTenantCounter.currentTest.set(context.requiredTestMethod.name)

    override fun afterEach(context: ExtensionContext) = TestTenantCounter.currentTest.remove()

    override fun afterAll(context: ExtensionContext) {
        val testClass = context.requiredTestClass
        if (mode == Mode.OFF || testClass.enclosingClass != null) return
        if (AnnotationSupport.findAnnotation(testClass, SkipCatalogInvariants::class.java).isPresent) return
        // The tenant slug prefix IntegrationTestBase, TestFixture and ScenarioBuilder use for this class.
        val namespace = testClass.simpleName.lowercase().take(20)
        if (!TestTenantCounter.used(namespace)) return

        val jdbi = SpringExtension.getApplicationContext(context).getBean(Jdbi::class.java)
        val started = System.nanoTime()
        val skipped = skippedTests(testClass)
        val violations = CatalogInvariants.check(jdbi, "$namespace-%").filterNot { TestTenantCounter.testOf(it.tenantKey) in skipped }
        val elapsed = System.nanoTime() - started
        TestRunMetrics.invariantChecks.record(elapsed, elapsed)
        if (violations.isEmpty()) return

        val lines = violations.map { violation -> "$violation" + (TestTenantCounter.testOf(violation.tenantKey)?.let { " (left by `$it`)" } ?: "") }
        val message = "${testClass.simpleName} left ${violations.size} catalog invariant violation(s):\n" +
            lines.joinToString("\n") { "  $it" }
        when (mode) {
            Mode.REPORT -> {
                logger.warn(message)
                val dir = Path.of("build", "invariant-violations").also { Files.createDirectories(it) }
                Files.writeString(dir.resolve("${testClass.name}.txt"), lines.joinToString("\n") + "\n")
            }

            else -> throw AssertionError(message)
        }
    }

    /** The tests of [testClass] and its nested classes that opt out. */
    private fun skippedTests(testClass: Class<*>): Set<String> = (
        testClass.declaredMethods.filter { it.isAnnotationPresent(SkipCatalogInvariants::class.java) }.map { it.name } +
            testClass.declaredClasses.flatMap { skippedTests(it) }
        ).toSet()

    private enum class Mode { FAIL, REPORT, OFF }

    private companion object {
        val logger = LoggerFactory.getLogger(CatalogInvariantsExtension::class.java)
        val mode: Mode = (System.getProperty("epistola.test.invariants") ?: System.getenv("EPISTOLA_TEST_INVARIANTS"))
            ?.let { Mode.valueOf(it.uppercase()) } ?: Mode.FAIL
    }
}
