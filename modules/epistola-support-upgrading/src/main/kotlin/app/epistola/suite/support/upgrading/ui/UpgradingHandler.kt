// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.upgrading.ui

import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.support.hubFeatureCall
import app.epistola.suite.support.logTo
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.upgrading.CompatibilityCheckResult
import app.epistola.suite.upgrading.CompatibilitySyncPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * UI handler for the Support → Upgrading page: shows the compatibility-check results the company
 * side has recorded for the tenant (read-only), fetched live from the hub and grouped by the
 * Epistola version that was tested.
 */
@Component
class UpgradingHandler(
    private val compatibility: CompatibilitySyncPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()

        // One status to render, instead of a 500: OK / UNAVAILABLE / NOT_ENTITLED / ERROR.
        val result =
            hubFeatureCall {
                compatibility
                    .listCompatibilityResults(tenantId.key)
                    .groupBy { it.targetVersion }
                    .map { (version, results) -> VersionGroup(version, results.map { it.toView() }) }
                    .sortedByDescending { it.targetVersion }
            }
        result.logTo(log, tenantId.key.value, "compatibility")

        return ServerResponse.ok().page("upgrading/list") {
            "pageTitle" to "Upgrading - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "activeNavSection" to "upgrading"
            "status" to result.status.name
            "groups" to (result.value ?: emptyList<VersionGroup>())
        }
    }

    private fun CompatibilityCheckResult.toView(): ResultView = ResultView(
        catalogKey = catalogKey ?: "(all catalogs)",
        verdict = verdict.name,
        detail = detail.orEmpty(),
        occurredAt = FORMATTER.format(occurredAt.atOffset(ZoneOffset.UTC)),
    )

    data class VersionGroup(
        val targetVersion: String,
        val results: List<ResultView>,
    )

    data class ResultView(
        val catalogKey: String,
        val verdict: String,
        val detail: String,
        val occurredAt: String,
    )

    private companion object {
        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
    }
}
