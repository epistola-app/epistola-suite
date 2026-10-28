// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.NoReleaseDeployedException
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.commands.UndeployCatalogRelease
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.mcp.dto.EnvironmentInfo
import app.epistola.suite.mcp.dto.EnvironmentsInfo
import app.epistola.suite.mcp.support.mcpCatalog
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * The tenant's environments, what is deployed to them, and deploying (beta). Each write dispatches
 * the command the UI and REST use, so the same checks, refusals and `ENVIRONMENT_DEPLOY` permission
 * apply. Generating documents is not offered over MCP; `preview_document` is the only rendering.
 */
@Component
class EnvironmentMcpTools {

    @McpTool(
        name = "list_environments",
        description = "The tenant's environments, each with the catalog versions deployed to it: releases (`version`) " +
            "and drafts rendered live (`version` null, `draftName` set), with how many documents were requested through " +
            "each. An environment's `id` is the `environmentId` `preview_document` and `analyze_template_data` take; " +
            "`production` environments serve releases only. To change what is deployed, use `deploy_release` and `undeploy_release`.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listEnvironments(): EnvironmentsInfo = EnvironmentsInfo.from(
        ListEnvironments(mcpTenantId()).query(),
        ListDeployments(mcpTenantKey()).query(),
    )

    @McpTool(
        name = "deploy_release",
        description = "Beta. Deploy a catalog release to an environment, so documents generated there can use it: " +
            "beside the releases of that catalog it already serves, or in their place with `replace`. `version` is a " +
            "release number, or a draft's `versionId` to serve that draft live (refused for a `production` environment). " +
            "Refused when a release it is pinned to is not served there, naming them, unless `includeDependencies` deploys " +
            "those along. Returns the environment as `list_environments` shows it. This changes what documents are " +
            "generated with, so deploy only when the user asks to. Requires the ENVIRONMENT_DEPLOY permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun deployRelease(
        @McpToolParam(description = "The environment's `id`, from `list_environments`.")
        environmentId: String,
        @McpToolParam(description = "The catalog: its address `namespace/key` from `list_catalogs`, or its key alone while one catalog has it.")
        catalogId: String,
        @McpToolParam(description = "The release number (MAJOR.MINOR.PATCH), or a draft's `versionId`. From `list_catalog_releases`.")
        version: String,
        @McpToolParam(description = "Also deploy the releases of other catalogs it is pinned to that the environment does not serve yet. Default false.", required = false)
        includeDependencies: Boolean? = null,
        @McpToolParam(description = "Stop serving the catalog's other releases in this environment. Default false: served beside them.", required = false)
        replace: Boolean? = null,
    ): EnvironmentInfo {
        val environment = environment(environmentId)
        DeployCatalogRelease(environment, mcpCatalog(catalogId), version.trim(), includeDependencies == true, replace = replace == true).execute()
        return describe(environment)
    }

    @McpTool(
        name = "undeploy_release",
        description = "Beta. Stop an environment serving a release (or a draft, by its `versionId`) of a catalog. " +
            "Documents generated there no longer use it; when it was the only one of that catalog, they cannot use the " +
            "catalog at all. Refused when the environment does not serve it. Returns the environment as " +
            "`list_environments` shows it. Undeploy only when the user asks to. Requires the ENVIRONMENT_DEPLOY permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false),
    )
    fun undeployRelease(
        @McpToolParam(description = "The environment's `id`, from `list_environments`.")
        environmentId: String,
        @McpToolParam(description = "The catalog: its address `namespace/key` from `list_catalogs`, or its key alone while one catalog has it.")
        catalogId: String,
        @McpToolParam(description = "The deployed release number, or the deployed draft's `versionId`, from `list_environments`.")
        version: String,
    ): EnvironmentInfo {
        val environment = environment(environmentId)
        val catalog = mcpCatalog(catalogId)
        if (!UndeployCatalogRelease(environment, catalog, version.trim()).execute()) {
            throw NoReleaseDeployedException(environment.tenantKey, environment.key, catalog.key, version.trim())
        }
        return describe(environment)
    }

    /** The environment [environmentId] names in this tenant; refused when there is none. */
    private fun environment(environmentId: String): EnvironmentId {
        val id = EnvironmentId(EnvironmentKey.of(environmentId.trim()), mcpTenantId())
        GetEnvironment(id).query() ?: throw EnvironmentNotFoundException(id.tenantKey, id.key)
        return id
    }

    private fun describe(environment: EnvironmentId): EnvironmentInfo = EnvironmentInfo.from(
        GetEnvironment(environment).query() ?: throw EnvironmentNotFoundException(environment.tenantKey, environment.key),
        ListDeployments(environment.tenantKey, environmentKey = environment.key).query(),
    )
}
