package app.epistola.suite.themes

import app.epistola.suite.common.ids.ThemeId

/**
 * Thrown when a requested theme does not exist.
 */
class ThemeNotFoundException(
    val themeId: ThemeId,
) : RuntimeException("Theme not found: $themeId")

/**
 * Thrown when attempting to delete a theme that is in use.
 * This includes being set as a tenant's default theme or referenced by templates.
 */
class ThemeInUseException(
    val themeId: ThemeId,
    reason: String,
) : RuntimeException("Cannot delete theme $themeId: $reason")

/**
 * Thrown when attempting to delete the last theme for a tenant.
 * At least one theme must always exist per tenant.
 */
class LastThemeException(
    val themeId: ThemeId,
) : RuntimeException("Cannot delete theme $themeId: it is the last theme for this tenant")
