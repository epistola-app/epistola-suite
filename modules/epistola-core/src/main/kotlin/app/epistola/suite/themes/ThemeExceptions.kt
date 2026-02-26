package app.epistola.suite.themes

import app.epistola.suite.common.ids.ThemeKey

/**
 * Thrown when a requested theme does not exist.
 */
class ThemeNotFoundException(
    val themeId: ThemeKey,
) : RuntimeException("Theme not found: $themeId")

/**
 * Thrown when attempting to delete a theme that is in use.
 * This includes being set as a tenant's default theme or referenced by templates.
 */
class ThemeInUseException(
    val themeId: ThemeKey,
    reason: String,
) : RuntimeException("Cannot delete theme $themeId: $reason")

/**
 * Thrown when attempting to delete the last theme for a tenant.
 * At least one theme must always exist per tenant.
 */
class LastThemeException(
    val themeId: ThemeKey,
) : RuntimeException("Cannot delete theme $themeId: it is the last theme for this tenant")
