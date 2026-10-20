// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration.preflight

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection

/** How [UpgradePreflights] runs its checks, whatever they check. */
@Tag("unit")
class UpgradePreflightsTest {

    private val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> null } as Connection

    private class Check(
        override val id: String,
        val applies: Boolean = true,
        val problems: List<UpgradePreflightProblem> = emptyList(),
        val error: Exception? = null,
    ) : UpgradePreflightCheck {
        override val upgrade = "1.0 to 2.0"
        var ran = false
        override fun appliesTo(context: UpgradePreflightContext) = applies
        override fun run(context: UpgradePreflightContext): List<UpgradePreflightProblem> {
            ran = true
            error?.let { throw it }
            return problems
        }
    }

    private fun problem(summary: String) = UpgradePreflightProblem("acme", summary, "Fix it.", listOf("in template invoice"))

    @Test
    fun `every check that applies runs, and the report gathers what all of them found`() {
        val first = Check("first", problems = listOf(problem("one thing")))
        val second = Check("second", problems = listOf(problem("another thing")))
        val elsewhere = Check("elsewhere", applies = false, problems = listOf(problem("never seen")))

        val report = UpgradePreflights.run(connection, checks = listOf(first, second, elsewhere))

        assertThat(report.problems.map { (check, problem) -> check.id to problem.summary }).containsExactly("first" to "one thing", "second" to "another thing")
        assertThat(elsewhere.ran).`as`("a check the database is not at is not run").isFalse()
        assertThat(report.passed).isFalse()
        assertThat(report.describe())
            .contains("[first, 1.0 to 2.0] Tenant 'acme': one thing")
            .contains("[second, 1.0 to 2.0] Tenant 'acme': another thing")
            .contains("  - in template invoice")
    }

    @Test
    fun `a check that cannot run refuses the upgrade, saying how to skip it`() {
        val broken = Check("broken", error = IllegalStateException("relation \"x\" does not exist"))

        val report = UpgradePreflights.run(connection, checks = listOf(broken, Check("fine")))

        assertThat(report.passed).isFalse()
        assertThat(report.failed.map { it.check.id }).containsExactly("broken")
        assertThat(report.describe()).contains("The check could not run: relation \"x\" does not exist").contains("epistola.migration.preflight.skip=broken")
    }

    @Test
    fun `a check the operator skipped is not run`() {
        val skipped = Check("noisy", problems = listOf(problem("would refuse")))

        val report = UpgradePreflights.run(connection, skipped = setOf("noisy"), checks = listOf(skipped))

        assertThat(skipped.ran).isFalse()
        assertThat(report.outcomes.single()).isInstanceOf(UpgradePreflightOutcome.Skipped::class.java)
        assertThat(report.passed).isTrue()
    }

    @Test
    fun `the registered checks have distinct kebab-case ids, which skipping them relies on`() {
        val ids = UpgradePreflights.checks.map { it.id }

        assertThat(ids).doesNotHaveDuplicates().allMatch { it.matches(Regex("^[a-z0-9]+(-[a-z0-9]+)*$")) }
        assertThat(ids).contains(CatalogDependencyCycleCheck.id, MissingCatalogCheck.id)
    }
}
