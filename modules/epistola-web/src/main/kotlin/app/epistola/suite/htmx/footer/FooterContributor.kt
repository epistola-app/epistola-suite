package app.epistola.suite.htmx.footer

import app.epistola.suite.htmx.UiRequestContext

/**
 * Module-contributed footer chrome.
 *
 * The host shell's footer no longer hardcodes feature-specific markup (scripts, floating action
 * buttons, …). A feature module implements [FooterContributor] as a `@Component` and returns the
 * Thymeleaf fragments to inject, deciding visibility itself from [UiRequestContext.hasPermission]
 * and/or [UiRequestContext.isFeatureEnabled]. [FooterFragmentResolver] collects all contributors
 * per request and the footer template `th:replace`s each fragment. Fragments may read `tenantId`
 * and the other shell model attributes.
 */
interface FooterContributor {
    fun fragments(context: UiRequestContext): List<FooterFragment>
}

/** A Thymeleaf fragment reference (`template :: fragment`) to inject into the footer. */
data class FooterFragment(
    val template: String,
    val fragment: String,
)
