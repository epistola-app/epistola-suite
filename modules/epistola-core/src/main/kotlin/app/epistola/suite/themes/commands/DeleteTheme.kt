// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.queries.FindResourceUsages
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.themes.ThemeInUseException
import app.epistola.suite.themes.ThemeStore
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Deletes a theme by ID.
 *
 * Constraint: cannot delete the tenant's current default theme — clear the
 * tenant default first (themes are optional, so zero themes is a valid state).
 *
 * Refused while anything uses it: in its own draft, or in another catalog (by the resource graph,
 * [FindResourceUsages]). Either would be left naming a theme its catalog's draft reads and no longer has.
 */
data class DeleteTheme(
    val id: ThemeId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef?,
) : CatalogReleaseScoped,
    Command<Boolean>,
    RequiresPermission {
    override val permission = Permission.THEME_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class DeleteThemeHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteTheme, Boolean> {
    /**
     * Deletes a theme by ID.
     * Returns true if a theme was deleted, false if not found.
     * @throws ThemeInUseException if the theme is the tenant's default theme, or anything still uses it
     */
    override fun handle(command: DeleteTheme): Boolean {
        requireCatalogEditable(command.id.catalogId)
        return jdbi.withHandle<Boolean, Exception> { handle ->
            val at = handle.versionOf(command.id.catalogId, command)
            val isDefaultTheme = handle.createQuery(
                """
                SELECT COUNT(*) FROM tenants t
                JOIN version_themes th ON th.tenant_key = t.id AND th.resource_id = t.default_theme_resource_id
                WHERE t.id = :tenantId
                  AND th.id = :themeId
                  AND th.version_id = :v
                """,
            )
                .bind("tenantId", command.id.tenantKey)
                .bind("themeId", command.id.key)
                .bindVersion("v", at.versionId)
                .mapTo<Long>()
                .one() > 0

            if (isDefaultTheme) {
                throw ThemeInUseException(command.id.key, "it is the tenant's default theme")
            }

            // In use in this draft (contract `deleteTheme`: refused while the draft uses it): as the
            // catalog's default theme, a template's theme or a variant's override. Deleting it would
            // leave them naming a theme the draft no longer has.
            val users = handle.createQuery(
                """
                WITH theme AS (
                    SELECT resource_id FROM version_themes
                    WHERE tenant_key = :tenantId AND id = :themeId AND version_id = :v
                )
                SELECT 'the catalog''s default theme' FROM catalog_versions v, theme
                WHERE v.tenant_key = :tenantId AND v.version_id = :v AND v.default_theme_resource_id = theme.resource_id
                UNION ALL
                SELECT 'template ' || dt.id FROM version_templates dt, theme
                WHERE dt.tenant_key = :tenantId AND dt.version_id = :v AND dt.theme_resource_id = theme.resource_id
                UNION ALL
                SELECT 'variant ' || dt.id || '/' || tv.id FROM version_template_variants tv
                JOIN version_templates dt ON dt.tenant_key = tv.tenant_key AND dt.version_id = tv.version_id AND dt.resource_id = tv.template_resource_id
                WHERE tv.tenant_key = :tenantId AND tv.version_id = :v
                  AND tv.template_model -> 'themeRef' ->> 'type' = 'override'
                  AND tv.template_model -> 'themeRef' ->> 'themeKey' = :themeId
                  AND COALESCE(tv.template_model -> 'themeRef' ->> 'catalogKey', :catalogKey) = :catalogKey
                """,
            )
                .bind("tenantId", command.id.tenantKey)
                .bind("themeId", command.id.key)
                .bind("catalogKey", command.id.catalogId.key.value)
                .bindVersion("v", at.versionId)
                .mapTo<String>()
                .list()
            if (users.isNotEmpty()) {
                throw ThemeInUseException(command.id.key, "this version still uses it: " + listed(users))
            }
            val elsewhere = FindResourceUsages(command.id.catalogId, command.catalogVersion).query()["theme:${command.id.key.value}"].orEmpty()
                .map { "${it.referenceType} ${it.referencedByName} (catalog ${it.referencedByCatalog})" }
            if (elsewhere.isNotEmpty()) {
                throw ThemeInUseException(command.id.key, "other catalogs use it: " + listed(elsewhere))
            }

            DraftResources.delete(handle, at, ThemeStore.THEME, command.id.key.value)
        }
    }

    private fun listed(users: List<String>) = users.take(5).joinToString() + if (users.size > 5) ", and ${users.size - 5} more" else ""
}
