package app.epistola.generation

import java.time.ZoneId
import java.util.Locale

/**
 * Render-scoped *formatting culture*: the locale and timezone that drive every
 * locale-sensitive formatter in a single render (`$formatDate`,
 * `$formatLocaleNumber`, `sys.render.time`).
 *
 * Resolved once per render by `TenantLocaleResolver` (variant attribute →
 * tenant default → app default) and threaded to the PDF renderer as ONE value
 * instead of a growing list of standalone parameters. When timezone (or later
 * currency/calendar) gains per-tenant resolution, it becomes a field here
 * rather than a fresh threading campaign through every `renderPdf*` signature.
 *
 * Scope is deliberately narrow — formatting culture only. Fonts, assets, render
 * mode and rendering defaults are separate concerns and must **not** be folded
 * in here; doing so would just relocate a long parameter list into a god-bag.
 *
 * [DEFAULT] is backed by the sibling defaults [DEFAULT_LOCALE] /
 * [DEFAULT_RENDER_TIMEZONE] and equals what the resolver produces for an
 * untouched default install. That equality is what lets
 * [app.epistola.generation.expression.CompositeExpressionEvaluator.forCulture]
 * short-circuit with zero allocation on the common path — so keep
 * [DEFAULT_LOCALE] aligned with the shipped `epistola.i18n.default-locale`.
 */
data class RenderCulture(
    val locale: Locale = DEFAULT_LOCALE,
    val timeZone: ZoneId = DEFAULT_RENDER_TIMEZONE,
) {
    companion object {
        /** Culture for the untouched default install — the no-op fast path. */
        val DEFAULT = RenderCulture()
    }
}
