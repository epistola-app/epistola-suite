package app.epistola.suite.htmx

import app.epistola.suite.common.ids.TenantKey

/**
 * Lightweight per-request context handed to UI-contribution SPIs (navigation, footer chrome, …).
 *
 * Carries the request's tenant and a resolved [hasPermission] predicate. A contributor that needs
 * feature-toggle state injects `FeatureToggleService` and looks it up by [tenantKey]; those reads
 * are cached per request (see `FeatureToggleService.withRequestCache`), so the lookup is cheap and
 * feature knowledge stays in the owning module rather than the host.
 */
data class UiRequestContext(
    val tenantKey: TenantKey,
    val hasPermission: (String) -> Boolean,
)
