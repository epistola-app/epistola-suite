// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.dependencies.MissingDependencyReason
import app.epistola.suite.environments.ReleaseDependenciesMissingException

/**
 * A deployment refused because the environment does not serve the releases of other catalogs the
 * release renders with, as the environment and catalog pages show it (`environments/deploy-refusal`).
 *
 * @property deployableAlong whether to offer "Deploy with its dependencies": every missing release
 *   is one the tenant has and only the environment lacks
 *   ([ReleaseDependenciesMissingException.deployableAlong]). Otherwise the page says why it is not.
 * @property deployUrl where the offer posts, with [fields] and `includeDependencies=true`: the same
 *   deploy action that was refused, so it carries the release already chosen.
 */
data class DeployRefusalView(
    val catalogKey: String,
    val version: String,
    val environmentKey: String,
    val includedDependencies: Boolean,
    val missing: List<MissingDependencyView>,
    val deployableAlong: Boolean,
    val deployUrl: String,
    val fields: Map<String, String>,
) {
    /** One missing release, and why, in words. */
    data class MissingDependencyView(val label: String, val reason: String, val explanation: String)

    companion object {
        fun of(failure: ReleaseDependenciesMissingException, deployUrl: String, fields: Map<String, String>) = DeployRefusalView(
            catalogKey = failure.catalogKey.value,
            version = failure.version,
            environmentKey = failure.environmentKey.value,
            includedDependencies = failure.includedDependencies,
            missing = failure.missing.map { MissingDependencyView(it.toString(), it.reason.name, explain(it.reason)) },
            deployableAlong = failure.deployableAlong,
            deployUrl = deployUrl,
            fields = LinkedHashMap(fields),
        )

        private fun explain(reason: MissingDependencyReason): String = when (reason) {
            MissingDependencyReason.NOT_DEPLOYED -> "this tenant has it, but this environment does not serve it"
            MissingDependencyReason.NOT_INSTALLED -> "this tenant does not have this release; install it first"
            MissingDependencyReason.UNAVAILABLE -> "this tenant does not have this release, and it is not fetched from its registry here; install it first"
        }
    }
}
