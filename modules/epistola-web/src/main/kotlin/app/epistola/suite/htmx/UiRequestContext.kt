package app.epistola.suite.htmx

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.Permission

/**
 * Lightweight per-request context handed to UI-contribution SPIs (navigation, footer chrome, …).
 *
 * Carries the request's tenant and a resolved [hasPermission] check (typed on [Permission], no
 * magic strings). A contributor that needs feature-toggle state reads it through the
 * `ResolveFeatureToggles` query keyed by [tenantKey]; those reads are cached per request
 * (`FeatureToggleService.withRequestCache`), so feature knowledge stays in the owning module.
 */
data class UiRequestContext(
    val tenantKey: TenantKey,
    val hasPermission: (Permission) -> Boolean,
)
