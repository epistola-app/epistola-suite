// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.contracts

/**
 * Thrown when a contract data-model change is backwards-incompatible and the
 * caller has not confirmed publishing it (e.g. REST `forceUpdate = false`).
 *
 * @property breakingChanges Human-readable descriptions of the detected breaking changes.
 */
class ContractPublishConflictException(
    val breakingChanges: List<String>,
) : RuntimeException(
    "Schema change is backwards-incompatible; confirm to publish it. " +
        "Breaking changes: ${breakingChanges.joinToString()}",
)
