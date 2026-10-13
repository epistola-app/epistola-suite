// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx.footer

import app.epistola.suite.htmx.UiRequestContext
import org.springframework.stereotype.Component

/** A footer fragment as rendered by the shell template: `template :: fragment`. */
data class FooterFragmentView(val ref: String)

/**
 * Collects the footer fragments contributed by all [FooterContributor]s for one request, and the
 * installation-wide status fragments of all [FooterStatusContributor]s.
 */
@Component
class FooterFragmentResolver(
    private val contributors: List<FooterContributor>,
    private val statusContributors: List<FooterStatusContributor> = emptyList(),
) {
    fun resolve(context: UiRequestContext): List<FooterFragmentView> = contributors
        .flatMap { it.fragments(context) }
        .map { FooterFragmentView("${it.template} :: ${it.fragment}") }

    fun resolveStatus(): List<FooterFragmentView> = statusContributors
        .flatMap { it.fragments() }
        .map { FooterFragmentView("${it.template} :: ${it.fragment}") }
}
