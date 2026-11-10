// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.handlers.ThemePicker
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class SettingsTabHandler(
    private val detailHelper: TemplateDetailHelper,
) {
    fun settings(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()

        val picker = ThemePicker.of(ctx.templateId.catalogId, ctx.catalogVersion, request.catalogSegment())

        return detailHelper.renderDetailPage(
            ctx,
            "settings",
            mapOf(
                "themes" to picker.themes,
                "themeCatalogs" to picker.themeCatalogs,
                "otherThemeCatalogs" to picker.otherThemeCatalogs,
                "declareUrl" to picker.declareUrl,
                // Alpha: the Location section is absent unless the tenant has relocation on.
                "resourceRelocationEnabled" to
                    (ResolveFeatureToggles(ctx.templateId.tenantKey).query()[KnownFeatures.RESOURCE_RELOCATION] == true),
            ),
        )
    }
}
