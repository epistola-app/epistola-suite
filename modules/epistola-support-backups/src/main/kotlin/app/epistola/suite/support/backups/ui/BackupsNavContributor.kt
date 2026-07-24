// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.backups.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component

/**
 * Adds the Backups item to the Support nav group when the `support-backups` feature is available —
 * the local toggle is on **and** the installation is entitled to it (hub-gated).
 */
@Component
class BackupsNavContributor : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        val available = ResolveAvailableFeatures(context.tenantKey).query()
        if (available[KnownFeatures.SUPPORT_BACKUPS] != true) return emptyList()
        return listOf(
            NavItem("support", "backups", "Backups", "backups", order = 20, stage = KnownFeatures.stageOf(KnownFeatures.SUPPORT_BACKUPS)),
        )
    }
}
