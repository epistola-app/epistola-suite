package app.epistola.suite.htmx

import app.epistola.suite.common.ids.TenantKey

/**
 * Lightweight per-request context handed to UI-contribution SPIs (navigation, footer chrome, …).
 *
 * Feature-toggle state is intentionally absent: a contributor that needs it injects
 * `FeatureToggleService` itself (feature modules already depend on epistola-core) and looks it up
 * with [tenantKey]. This keeps feature knowledge in the owning module rather than the host.
 */
data class UiRequestContext(
    val tenantKey: TenantKey,
    val hasPermission: (String) -> Boolean,
)
