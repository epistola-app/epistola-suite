// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration.preflight

import java.sql.Connection
import javax.sql.DataSource

/**
 * Before the migrations run, whether the upgrade can finish.
 *
 * An upgrade is refused when the database holds what it cannot carry over, while nothing has been
 * changed yet: a migration that failed later could not undo the ones before it, and the previous
 * version could no longer read the half-converted database. Refused before the first one, the
 * database stays as it was and the installation keeps working on the old version while it is
 * fixed there.
 *
 * Each concern is an [UpgradePreflightCheck], listed in [UpgradePreflights.checks]: it says which
 * upgrade it guards, whether the database is at that point, and what it finds. The checks run on
 * a plain connection, before Flyway, in the migration step and in embedded mode
 * (`FlywayConfig`), so they read the schema the database has now, not the one the app is built for.
 */
interface UpgradePreflightCheck {
    /** Stable id, kebab-case: what the report names and an operator skips it by. */
    val id: String

    /** The upgrade this check guards, as the report names it, e.g. `1.x to 2.0`. */
    val upgrade: String

    /** Whether the database is at the point this check is about. One that is not is not run. */
    fun appliesTo(context: UpgradePreflightContext): Boolean

    /** What would stop the upgrade from finishing; empty when nothing would. */
    fun run(context: UpgradePreflightContext): List<UpgradePreflightProblem>
}

/**
 * One thing to fix before upgrading: [summary] says what is wrong, [fix] what to do, [details] where
 * (each resource involved). [tenantKey] is null for a problem of the whole installation.
 */
data class UpgradePreflightProblem(
    val tenantKey: String?,
    val summary: String,
    val fix: String,
    val details: List<String> = emptyList(),
)

/**
 * What the checks of one run share: the [connection], and facts several checks read, computed once
 * ([shared]) — the 1.x references both 1.x checks look at, for instance.
 */
class UpgradePreflightContext(val connection: Connection) {
    private val facts = mutableMapOf<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T> shared(key: String, compute: (Connection) -> T): T = facts.getOrPut(key) { compute(connection) } as T
}

/** How each check of a run ended. */
sealed interface UpgradePreflightOutcome {
    val check: UpgradePreflightCheck

    /** The database is not at the point the check is about. */
    data class NotApplicable(override val check: UpgradePreflightCheck) : UpgradePreflightOutcome

    /** The operator skipped it (`epistola.migration.preflight.skip`). */
    data class Skipped(override val check: UpgradePreflightCheck) : UpgradePreflightOutcome

    data class Ran(override val check: UpgradePreflightCheck, val problems: List<UpgradePreflightProblem>) : UpgradePreflightOutcome

    /** The check could not run: the upgrade is refused rather than let through unchecked. */
    data class Failed(override val check: UpgradePreflightCheck, val error: Throwable) : UpgradePreflightOutcome
}

/** The outcome of every check; [passed] when none found a problem and none failed. */
data class UpgradePreflightReport(val outcomes: List<UpgradePreflightOutcome>) {
    val problems: List<Pair<UpgradePreflightCheck, UpgradePreflightProblem>>
        get() = outcomes.filterIsInstance<UpgradePreflightOutcome.Ran>().flatMap { ran -> ran.problems.map { ran.check to it } }

    val failed: List<UpgradePreflightOutcome.Failed> get() = outcomes.filterIsInstance<UpgradePreflightOutcome.Failed>()

    val passed: Boolean get() = problems.isEmpty() && failed.isEmpty()

    fun describe(): String = buildString {
        appendLine("The upgrade cannot start: it could not finish with what the database holds now.")
        appendLine("Nothing was changed. Fix the following on the version you run now, then run the upgrade again.")
        for ((check, problem) in problems) {
            appendLine()
            appendLine("[${check.id}, ${check.upgrade}] ${problem.tenantKey?.let { "Tenant '$it': " }.orEmpty()}${problem.summary}")
            appendLine("  ${problem.fix}")
            problem.details.forEach { appendLine("  - $it") }
        }
        for (failure in failed) {
            appendLine()
            appendLine("[${failure.check.id}, ${failure.check.upgrade}] The check could not run: ${failure.error.message ?: failure.error::class.simpleName}")
            appendLine("  If you are sure the upgrade can finish anyway, skip it: epistola.migration.preflight.skip=${failure.check.id}")
        }
    }.trimEnd()
}

/** Refused before the migrations: thrown with the [report] that says what to fix. */
class UpgradePreflightRefused(val report: UpgradePreflightReport) : IllegalStateException(report.describe())

object UpgradePreflights {
    /** Every check, in the order the report lists them. A new check is added here. */
    val checks: List<UpgradePreflightCheck> = listOf(
        CatalogDependencyCycleCheck,
        MissingCatalogCheck,
    )

    /** Runs [checks] but those [skipped] by id, each where it applies. */
    fun run(connection: Connection, skipped: Set<String> = emptySet(), checks: List<UpgradePreflightCheck> = this.checks): UpgradePreflightReport {
        val context = UpgradePreflightContext(connection)
        return UpgradePreflightReport(checks.map { outcome(it, context, skipped) })
    }

    private fun outcome(check: UpgradePreflightCheck, context: UpgradePreflightContext, skipped: Set<String>): UpgradePreflightOutcome {
        if (check.id in skipped) return UpgradePreflightOutcome.Skipped(check)
        return try {
            if (check.appliesTo(context)) UpgradePreflightOutcome.Ran(check, check.run(context)) else UpgradePreflightOutcome.NotApplicable(check)
        } catch (e: Exception) {
            UpgradePreflightOutcome.Failed(check, e)
        }
    }

    /** @throws UpgradePreflightRefused when a check found a problem or could not run. */
    fun require(dataSource: DataSource, skipped: Set<String> = emptySet()): UpgradePreflightReport {
        val report = dataSource.connection.use { run(it, skipped) }
        if (!report.passed) throw UpgradePreflightRefused(report)
        return report
    }
}
