// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.environments.queries.GenerationReadiness
import app.epistola.suite.environments.queries.GetGenerationReadiness
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.home.HomeNotice
import app.epistola.suite.htmx.home.HomeNoticeContributor
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import org.springframework.stereotype.Component

/**
 * Tells someone landing on the tenant home why documents cannot be made yet: catalogs whose
 * "Published in 1.x" draft still has to be published after an upgrade from 1.x, and environments that serve nothing, so every request naming them fails. Right
 * after an upgrade from 1.x both are true, and nothing else would say so.
 */
@Component
class GenerationReadinessHomeNoticeContributor : HomeNoticeContributor {

    override fun notices(context: UiRequestContext): List<HomeNotice> {
        if (!context.hasPermission(Permission.TEMPLATE_VIEW)) return emptyList()
        val readiness = GetGenerationReadiness(context.tenantKey).query()
        if (readiness.ready) return emptyList()
        return listOf(
            HomeNotice(
                template = "environments/home-notice",
                fragment = "banner",
                data = GenerationReadinessNoticeData(context.tenantKey.value, readiness, context.hasPermission(Permission.ENVIRONMENT_DEPLOY)),
                // Before catalog updates: this is about documents failing now.
                order = 15,
            ),
        )
    }
}

/** What the banner says; the wording is a rule, so it lives here where it can be tested. */
data class GenerationReadinessNoticeData(
    val tenantId: String,
    private val readiness: GenerationReadiness,
    /** The viewer can deploy, so the banner offers it. */
    val mayDeploy: Boolean,
) {
    /**
     * The upgrade from 1.x cut no release: each catalog keeps what it last published as a draft,
     * "Published in 1.x", which publishing and deploying puts back live (ADR 0035).
     */
    val upgrading: String? get() = readiness.publishedIn1x.takeIf { it.isNotEmpty() }?.let { names ->
        val list = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
        "Upgrade to Epistola 2.0: publish $list and deploy ${if (names.size == 1) "it" else "them"} to restore what was live in 1.x. " +
            "Where a catalog has a draft \"Published in 1.x\", publish that one: it holds what 1.x published."
    }

    val idle: String? get() = readiness.idleEnvironments.map { it.second }.takeIf { it.isNotEmpty() }?.let { names ->
        val list = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
        (if (names.size == 1) "$list uses no catalog yet" else "$list use no catalog yet") + ", so documents requested there fail."
    }

    /** The one environment to open, when there is exactly one; else the list. */
    val environmentKey: String? get() = readiness.idleEnvironments.singleOrNull()?.first?.value
}
