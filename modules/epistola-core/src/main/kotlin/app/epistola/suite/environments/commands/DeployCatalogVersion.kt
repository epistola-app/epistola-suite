// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.commands

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.CatalogDeployment
import app.epistola.suite.environments.DeploymentAction
import app.epistola.suite.environments.DeploymentLog
import app.epistola.suite.environments.DeploymentRows
import app.epistola.suite.environments.DraftDeploymentStore
import app.epistola.suite.environments.EnvironmentDeploymentRefusedException
import app.epistola.suite.environments.MissingDependencyReason
import app.epistola.suite.environments.ReleaseDependencies
import app.epistola.suite.environments.ReleaseDependenciesMissingException
import app.epistola.suite.environments.ReleaseNotDeployableException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deploys a version of a catalog to an environment (contract `deployCatalogVersion`): [version] names
 * a release by its number or its `versionId`, or a draft by its `versionId`.
 *
 * **A draft** is served live, at most one per catalog: deploying another replaces it, logged as
 * undeployed. A production environment serves only releases and refuses a draft
 * ([EnvironmentDeploymentRefusedException]). A request through the environment that names no version
 * renders the deployed draft; one naming a range or a number, a release. Releasing the draft makes
 * the deployment one of that release, which keeps the draft's id; discarding it ends the deployment.
 * With [includeDependencies], the releases its declarations pin are deployed along; never drafts.
 *
 * **A release** is served beside any other releases of the catalog the environment already serves. Generation and preview naming the environment
 * render the highest release of the catalog it serves, or the highest that satisfies the request's
 * version selector, so deploying a higher release moves such requests to it and undeploying it moves
 * them back. A request already accepted keeps the release it was bound to.
 *
 * Deploying a release the environment already serves changes nothing and logs nothing.
 *
 * A release renders with the releases of other catalogs it was cut with, direct and transitive
 * ([ReleaseDependencies]): it is pinned to them, and renders them from the tenant's copy whatever the
 * environment serves ([ReleaseRenderSource][app.epistola.suite.generation.release.ReleaseRenderSource]).
 * So the environment uses them **implicitly**: they are not deployed, and nothing else it serves
 * changes (contract ADR 0003). They must be there to render from: one the tenant does not have
 * (`UNAVAILABLE`; this installation does not fetch from a registry while deploying) refuses the
 * deployment with [ReleaseDependenciesMissingException], and nothing is deployed.
 *
 * With [includeDependencies] they are also deployed **explicitly**, the ones the environment does not
 * serve yet, in the same transaction: so it serves those catalogs' own templates too.
 *
 * With [replace], the release takes the place of the catalog's other releases in the environment:
 * they stop being served, in the same transaction — the UI's "deploy", which shows one deployed
 * version per catalog per environment. A release another deployed release is pinned to keeps being
 * used implicitly. Without it the release is served beside them, as the contract's `deployRelease`
 * does.
 *
 * Every release deployed or replaced is logged in `environment_deployment_log`, in the same
 * transaction.
 *
 * Permission: `ENVIRONMENT_DEPLOY` (contract `deployRelease`).
 *
 * @return the deployment of the release asked for.
 */
data class DeployCatalogVersion(
    val environmentId: EnvironmentId,
    val catalogKey: CatalogKey,
    val version: String,
    val includeDependencies: Boolean = false,
    val replace: Boolean = false,
) : Command<CatalogDeployment>,
    RequiresPermission {
    override val permission get() = Permission.ENVIRONMENT_DEPLOY
    override val tenantKey get() = environmentId.tenantKey
}

@Component
class DeployCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val dependencies: ReleaseDependencies,
    private val dependencyStore: CatalogDependencyStore,
) : CommandHandler<DeployCatalogVersion, CatalogDeployment> {
    override fun handle(command: DeployCatalogVersion): CatalogDeployment = jdbi.inTransaction<CatalogDeployment, Exception> { handle ->
        val tenantKey = command.environmentId.tenantKey
        val environmentKey = command.environmentId.key
        // Locked, so deploys and undeploys on one environment run one after the other: the
        // dependency check below holds until this transaction commits.
        val production = DraftDeploymentStore.lockEnvironment(handle, tenantKey, environmentKey)

        val release = releaseNamed(handle, tenantKey, command.catalogKey, command.version)
        if (release == null) {
            val draft = DraftDeploymentStore.draft(handle, tenantKey, command.catalogKey, DraftRef.Id(command.version))
                ?: throw ReleaseNotDeployableException(command.catalogKey, command.version, "no such release or draft")
            return@inTransaction deployDraft(handle, command, production, draft.first, draft.second)
        }
        deployRelease(handle, command.copy(version = release))
    }

    /** Serves the release [command] names by its number, beside or in place of the catalog's others. */
    private fun deployRelease(handle: Handle, command: DeployCatalogVersion): CatalogDeployment {
        val tenantKey = command.environmentId.tenantKey
        val environmentKey = command.environmentId.key
        served(handle, command, command.catalogKey, command.version)?.let { deployment ->
            // Already deployed: replacing only takes the others away, each logged as undeployed, and
            // asking for its dependencies deploys the ones not served yet.
            if (command.replace) {
                replaceable(handle, command).forEach { stopServing(handle, command, it, log = true) }
                DraftDeploymentStore.undeploy(handle, tenantKey, environmentKey, command.catalogKey)
            }
            if (command.includeDependencies) {
                dependencies.missing(handle, tenantKey, environmentKey, command.catalogKey, command.version)
                    .filter { it.reason == MissingDependencyReason.NOT_DEPLOYED }
                    .forEach { deploy(handle, command, it.tenantCatalogKey, it.releaseVersion) }
            }
            return deployment
        }

        val missing = dependencies.missing(handle, tenantKey, environmentKey, command.catalogKey, command.version)
        // Used implicitly, so only one nothing could render from refuses.
        val unrenderable = missing.filter { it.reason != MissingDependencyReason.NOT_DEPLOYED }
            .map { if (it.reason == MissingDependencyReason.NOT_INSTALLED) it.copy(reason = MissingDependencyReason.UNAVAILABLE) else it }
        if (unrenderable.isNotEmpty()) {
            throw ReleaseDependenciesMissingException(environmentKey, command.catalogKey, command.version, unrenderable, includedDependencies = command.includeDependencies)
        }
        // Asked to deploy them too: the ones the environment does not serve yet.
        if (command.includeDependencies) missing.forEach { deploy(handle, command, it.tenantCatalogKey, it.releaseVersion) }
        // Replacing: the others stop being served first, so the release is logged as replacing the
        // one it took the place of ("made v1.1.0 live, replacing v1.0.0"); more than one replaced
        // are each logged as taken offline.
        val replaced = if (command.replace) replaceable(handle, command) else emptyList()
        replaced.forEach { stopServing(handle, command, it, log = replaced.size > 1) }
        // Replacing also takes a deployed draft away: what the UI shows deployed is then this release.
        if (command.replace) DraftDeploymentStore.undeploy(handle, tenantKey, environmentKey, command.catalogKey)
        return deploy(handle, command, command.catalogKey, command.version, previousVersion = replaced.singleOrNull())
    }

    /** The number of the release [version] names, by its number or its `versionId`; null when it names none. */
    private fun releaseNamed(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): String? = handle.createQuery(
        "SELECT version FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND (version = :v OR version_key = :v)",
    )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("v", version.trim())
        .mapTo<String>()
        .findOne()
        .orElse(null)

    /** Serves draft [versionId] in the environment, in place of a draft of the catalog it served. */
    private fun deployDraft(handle: Handle, command: DeployCatalogVersion, production: Boolean, versionId: UUID, label: String): CatalogDeployment {
        val tenantKey = command.environmentId.tenantKey
        val environmentKey = command.environmentId.key
        if (production) {
            throw EnvironmentDeploymentRefusedException(
                "'${environmentKey.value}' is a production environment: it serves only releases. Release $label first and deploy that.",
            )
        }
        val current = DraftDeploymentStore.list(handle, tenantKey, environmentKey, command.catalogKey).singleOrNull()
        if (current?.draftId == command.version) return deployment(handle, tenantKey, environmentKey, command.catalogKey, draftId = versionId)
        DraftDeploymentStore.undeploy(handle, tenantKey, environmentKey, command.catalogKey)
        handle.createUpdate(
            """
            INSERT INTO environment_catalog_deployments (tenant_key, environment_key, catalog_id, draft_id, deployed_at, deployed_by)
            VALUES (:t, :e, ${catalogAt("t", "c")}, :d, NOW(), :by)
            """,
        )
            .bind("t", tenantKey)
            .bind("e", environmentKey)
            .bind("c", command.catalogKey)
            .bind("d", versionId)
            .bind("by", currentUserIdOrNull()?.value)
            .execute()
        DeploymentLog.record(handle, tenantKey, environmentKey, command.catalogKey, DeploymentAction.DEPLOYED, null, draftLabel = label, versionKey = command.version)
        // The releases its declarations pin, deployed along when asked; a draft it names never is.
        if (command.includeDependencies) {
            val served = dependencies.served(handle, tenantKey, environmentKey).toSet()
            dependencyStore.declarationsOf(handle, tenantKey, versionId)
                .mapNotNull { declaration -> declaration.releaseVersion?.let { CatalogKey.of(declaration.catalogKey) to it } }
                .filter { it !in served }
                .forEach { (catalog, version) -> deploy(handle, command, catalog, version) }
        }
        return deployment(handle, tenantKey, environmentKey, command.catalogKey, draftId = versionId)
    }

    /**
     * The catalog's other releases the environment serves, which [command] replaces: all of them. A
     * release another one deployed there is pinned to keeps rendering, implicitly.
     */
    private fun replaceable(handle: Handle, command: DeployCatalogVersion): List<String> = handle.createQuery(
        """
        SELECT d.version FROM environment_catalog_deployments d
        WHERE d.tenant_key = :t AND d.environment_key = :e AND d.catalog_id = ${catalogAt("t", "c")} AND d.version <> :v
        """,
    )
        .bind("t", command.environmentId.tenantKey)
        .bind("e", command.environmentId.key)
        .bind("c", command.catalogKey)
        .bind("v", command.version)
        .mapTo<String>()
        .list()

    private fun stopServing(handle: Handle, command: DeployCatalogVersion, version: String, log: Boolean) {
        val tenantKey = command.environmentId.tenantKey
        handle.createUpdate(
            "DELETE FROM environment_catalog_deployments WHERE tenant_key = :t AND environment_key = :e AND catalog_id = ${catalogAt("t", "c")} AND version = :v",
        )
            .bind("t", tenantKey)
            .bind("e", command.environmentId.key)
            .bind("c", command.catalogKey)
            .bind("v", version)
            .execute()
        if (log) DeploymentLog.record(handle, tenantKey, command.environmentId.key, command.catalogKey, DeploymentAction.UNDEPLOYED, version)
    }

    /** The deployment, when the environment already serves this release. */
    private fun served(handle: Handle, command: DeployCatalogVersion, catalogKey: CatalogKey, version: String): CatalogDeployment? = DeploymentRows.find(
        handle,
        command.environmentId.tenantKey,
        command.environmentId.key,
        catalogKey,
        version = version,
    )

    private fun deployment(handle: Handle, tenantKey: TenantKey, environmentKey: EnvironmentKey, catalogKey: CatalogKey, version: String? = null, draftId: UUID? = null): CatalogDeployment = requireNotNull(DeploymentRows.find(handle, tenantKey, environmentKey, catalogKey, version, draftId))

    /** Serves the release in the environment and logs it. */
    private fun deploy(handle: Handle, command: DeployCatalogVersion, catalogKey: CatalogKey, version: String, previousVersion: String? = null): CatalogDeployment {
        val tenantKey = command.environmentId.tenantKey
        handle.createUpdate(
            """
            INSERT INTO environment_catalog_deployments (tenant_key, environment_key, catalog_id, version, deployed_at, deployed_by)
            VALUES (:t, :e, ${catalogAt("t", "c")}, :v, NOW(), :by)
            """,
        )
            .bind("t", tenantKey)
            .bind("e", command.environmentId.key)
            .bind("c", catalogKey)
            .bind("v", version)
            .bind("by", currentUserIdOrNull()?.value)
            .execute()
        DeploymentLog.record(handle, tenantKey, command.environmentId.key, catalogKey, DeploymentAction.DEPLOYED, version, previousVersion)
        return deployment(handle, tenantKey, command.environmentId.key, catalogKey, version = version)
    }
}
