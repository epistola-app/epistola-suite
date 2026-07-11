package app.epistola.suite.templates.contracts

import app.epistola.suite.documents.queries.RecentUsageImpact

/**
 * Thrown when a contract data-model change is backwards-incompatible and the
 * caller has not confirmed publishing it (e.g. REST `forceUpdate = false`).
 *
 * @property breakingChanges Human-readable descriptions of the detected breaking changes.
 * @property recentUsage Optional impact of the change on the input data of recently generated
 *   documents (#280), attached when the rejecting caller has computed it.
 */
class ContractPublishConflictException(
    val breakingChanges: List<String>,
    val recentUsage: RecentUsageImpact? = null,
) : RuntimeException(
    "Schema change is backwards-incompatible; confirm to publish it. " +
        "Breaking changes: ${breakingChanges.joinToString()}",
)
