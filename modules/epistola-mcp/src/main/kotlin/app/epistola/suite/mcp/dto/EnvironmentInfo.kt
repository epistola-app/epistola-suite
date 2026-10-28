// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.environments.CatalogDeployment
import app.epistola.suite.environments.Environment
import java.time.OffsetDateTime

/** The tenant's environments, each with the catalog versions deployed to it. */
data class EnvironmentsInfo(val environments: List<EnvironmentInfo>) {
    companion object {
        fun from(environments: List<Environment>, deployments: List<CatalogDeployment>): EnvironmentsInfo {
            val byEnvironment = deployments.groupBy { it.environmentKey }
            return EnvironmentsInfo(environments.map { EnvironmentInfo.from(it, byEnvironment[it.id].orEmpty()) })
        }
    }
}

/**
 * One environment: `id` is what `preview_document` and `analyze_template_data` take as
 * `environmentId`; `production` means it serves releases only, never a draft.
 */
data class EnvironmentInfo(
    val id: String,
    val name: String,
    val production: Boolean,
    val deployments: List<DeploymentInfo>,
) {
    companion object {
        fun from(environment: Environment, deployments: List<CatalogDeployment>) = EnvironmentInfo(
            id = environment.id.value,
            name = environment.name,
            production = environment.production,
            deployments = deployments.map(DeploymentInfo::from),
        )
    }
}

/**
 * A catalog version an environment serves: a release (`version`), or a draft rendered live
 * (`version` null, `draftName` set). `catalogId` is the catalog's address, as `list_catalogs` gives it.
 */
data class DeploymentInfo(
    val catalogId: String,
    val version: String?,
    val versionId: String?,
    val draftName: String?,
    val deployedAt: OffsetDateTime,
    val documentsRequested: Long,
    val lastUsedAt: OffsetDateTime?,
) {
    companion object {
        fun from(d: CatalogDeployment) = DeploymentInfo(
            catalogId = "${d.namespace}/${d.catalogKey.value}",
            version = d.version,
            versionId = d.versionId,
            draftName = d.draftName,
            deployedAt = d.deployedAt,
            documentsRequested = d.documentsRequested,
            lastUsedAt = d.lastUsedAt,
        )
    }
}
