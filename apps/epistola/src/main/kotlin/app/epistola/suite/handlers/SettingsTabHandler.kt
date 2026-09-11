// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.mediator.query
import app.epistola.suite.themes.queries.ListThemes
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class SettingsTabHandler(
    private val detailHelper: TemplateDetailHelper,
) {
    fun settings(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()

        val themes = ListThemes(tenantId = ctx.templateId.tenantId).query()
        val themeCatalogs = themes.groupBy { it.catalogKey.value }

        return detailHelper.renderDetailPage(
            ctx,
            "settings",
            mapOf(
                "themes" to themes,
                "themeCatalogs" to themeCatalogs,
                // Alpha: the Location section is absent unless the tenant has relocation on.
                "resourceRelocationEnabled" to
                    (ResolveFeatureToggles(ctx.templateId.tenantKey).query()[KnownFeatures.RESOURCE_RELOCATION] == true),
            ),
        )
    }
}
