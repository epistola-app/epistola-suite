// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.mcp.dto.EnvironmentsInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.query
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.stereotype.Component

/**
 * The tenant's environments and what is deployed to them, read-only. Deploying stays in the UI and
 * the REST API, so a person decides when a release is used to generate documents (`docs/mcp.md`).
 */
@Component
class EnvironmentMcpTools {

    @McpTool(
        name = "list_environments",
        description = "The tenant's environments, each with the catalog versions deployed to it: releases (`version`) " +
            "and drafts rendered live (`version` null, `draftName` set), with how many documents were requested through " +
            "each. An environment's `id` is the `environmentId` `preview_document` and `analyze_template_data` take; " +
            "`production` environments serve releases only. Read-only: deploying is done in the UI or the REST API.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listEnvironments(): EnvironmentsInfo = EnvironmentsInfo.from(
        ListEnvironments(mcpTenantId()).query(),
        ListDeployments(mcpTenantKey()).query(),
    )
}
