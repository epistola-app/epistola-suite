package app.epistola.suite.htmx.footer

import app.epistola.suite.htmx.UiRequestContext
import org.springframework.stereotype.Component

/** A footer fragment as rendered by the shell template: `template :: fragment`. */
data class FooterFragmentView(val ref: String)

/** Collects the footer fragments contributed by all [FooterContributor]s for one request. */
@Component
class FooterFragmentResolver(
    private val contributors: List<FooterContributor>,
) {
    fun resolve(context: UiRequestContext): List<FooterFragmentView> = contributors
        .flatMap { it.fragments(context) }
        .map { FooterFragmentView("${it.template} :: ${it.fragment}") }
}
