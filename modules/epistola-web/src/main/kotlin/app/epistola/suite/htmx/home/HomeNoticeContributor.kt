// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx.home

import app.epistola.suite.htmx.UiRequestContext

/**
 * Module-contributed notices for the top of the tenant home page.
 *
 * The host's tenant-home handler no longer hardcodes feature-specific banners. A feature module
 * implements [HomeNoticeContributor] as a `@Component` and returns the notices to render, deciding
 * visibility itself from [UiRequestContext.hasPermission] and/or the `ResolveFeatureToggles` query.
 * [HomeNoticeResolver] collects all contributors for a request and the home template `th:replace`s
 * each notice's fragment. The rendered fragment can read the notice via the `notice` loop variable
 * (e.g. `notice.data`), the same context-sharing mechanism the footer chrome uses.
 *
 * Return an empty list to render nothing — contributors decide when they have something to show.
 */
interface HomeNoticeContributor {
    fun notices(context: UiRequestContext): List<HomeNotice>
}

/**
 * A tenant-home notice: a Thymeleaf fragment reference ([template] `::` [fragment]) plus the [data]
 * the fragment renders, ordered by [order] (ascending).
 */
data class HomeNotice(
    val template: String,
    val fragment: String,
    val data: Any? = null,
    val order: Int = 0,
)
