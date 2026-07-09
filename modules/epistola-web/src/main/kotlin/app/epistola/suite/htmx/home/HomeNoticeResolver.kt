package app.epistola.suite.htmx.home

import app.epistola.suite.htmx.UiRequestContext
import org.springframework.stereotype.Component

/** A home notice as rendered by the host template: `ref` is `template :: fragment`. */
data class HomeNoticeView(val ref: String, val data: Any?)

/** Collects the tenant-home notices contributed by all [HomeNoticeContributor]s for one request. */
@Component
class HomeNoticeResolver(
    private val contributors: List<HomeNoticeContributor>,
) {
    fun resolve(context: UiRequestContext): List<HomeNoticeView> = contributors
        .flatMap { it.notices(context) }
        .sortedBy { it.order }
        .map { HomeNoticeView("${it.template} :: ${it.fragment}", it.data) }
}
