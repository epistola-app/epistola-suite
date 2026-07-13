package app.epistola.suite.htmx

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.Permission
import app.epistola.suite.security.PlatformRole

/**
 * Lightweight per-request context handed to UI-contribution SPIs (navigation, footer chrome, …).
 *
 * Carries the request's tenant, a resolved [hasPermission] check (typed on [Permission], no
 * magic strings) and a [hasPlatformRole] check for cross-tenant/platform-scoped items. A
 * contributor that needs feature-toggle state reads it through the `ResolveFeatureToggles` query
 * keyed by [tenantKey]; those reads are cached per request (`FeatureToggleService.withRequestCache`),
 * so feature knowledge stays in the owning module.
 */
data class UiRequestContext(
    val tenantKey: TenantKey,
    val hasPermission: (Permission) -> Boolean,
    val hasPlatformRole: (PlatformRole) -> Boolean = { false },
)
