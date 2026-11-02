// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import org.springframework.stereotype.Component

/**
 * Adds "Quality" to the Settings group: the tenant's quality providers (ADR 0047). For whoever may
 * manage the tenant's integrations (providers send its text out), while the quality feature is on.
 */
@Component
class QualityNavContributor : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        if (!context.hasPermission(Permission.INTEGRATIONS_MANAGE)) return emptyList()
        if (ResolveAvailableFeatures(context.tenantKey).query()[KnownFeatures.QUALITY] != true) return emptyList()
        return listOf(
            NavItem("settings", "quality-settings", "Quality", "quality/settings", order = 25, stage = KnownFeatures.stageOf(KnownFeatures.QUALITY)),
        )
    }
}
