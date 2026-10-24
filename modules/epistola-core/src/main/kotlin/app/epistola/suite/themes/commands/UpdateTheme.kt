// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.contentRevision
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.suite.themes.Theme
import app.epistola.suite.themes.crossCatalogRefs
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Updates a theme with partial updates (only provided fields are updated).
 *
 * [blockStylePresets] replaces the whole preset map. [mergeBlockStylePresets] and
 * [removeBlockStylePresets] change it per preset name instead, in the same statement, so a
 * client can add or drop one preset without resending the rest or racing another writer.
 */
data class UpdateTheme(
    val id: ThemeId,
    val name: String? = null,
    /** The new description; null keeps the current one, blank removes it (as [clearDescription] does). */
    val description: String? = null,
    val clearDescription: Boolean = false,
    val documentStyles: DocumentStyles? = null,
    val pageSettings: PageSettings? = null,
    val clearPageSettings: Boolean = false,
    val blockStylePresets: BlockStylePresets? = null,
    val clearBlockStylePresets: Boolean = false,
    val mergeBlockStylePresets: BlockStylePresets? = null,
    val removeBlockStylePresets: Set<String> = emptySet(),
    val spacingUnit: Float? = null,
    val clearSpacingUnit: Boolean = false,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The theme's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
    Command<Theme?>,
    RequiresPermission {
    override val permission = Permission.THEME_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        if (name != null) {
            validate("name", name.isNotBlank()) { "Name cannot be blank" }
            validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        }
        val changesPerPreset = mergeBlockStylePresets != null || removeBlockStylePresets.isNotEmpty()
        validate("blockStylePresets", !(changesPerPreset && (blockStylePresets != null || clearBlockStylePresets))) {
            "Replace or clear the presets, or merge and remove individual presets, not both"
        }
        validate("removeBlockStylePresets", mergeBlockStylePresets?.keys.orEmpty().none { it in removeBlockStylePresets }) {
            "A preset cannot be merged and removed in the same update"
        }
    }
}

@Component
class UpdateThemeHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val usedCatalogs: UsedCatalogDeclarer,
) : CommandHandler<UpdateTheme, Theme?> {
    override fun handle(command: UpdateTheme): Theme? {
        val saved = save(command)
        // What the saved resource uses of other catalogs is declared now, so the draft renders and releases with it.
        saved?.let { usedCatalogs.declare(command.id.catalogId, it.crossCatalogRefs(objectMapper)) }
        return saved
    }

    private fun save(command: UpdateTheme): Theme? {
        requireCatalogEditable(command.id.catalogId)

        val updates = mutableListOf<String>()
        val bindings = mutableMapOf<String, Any?>()

        if (command.name != null) {
            updates.add("name = :name")
            bindings["name"] = command.name
        }

        // Blank removes it, as clearDescription does (as UpdateDocumentTemplate's description).
        if (command.clearDescription || command.description?.isBlank() == true) {
            updates.add("description = NULL")
        } else if (command.description != null) {
            updates.add("description = :description")
            bindings["description"] = command.description
        }

        if (command.documentStyles != null) {
            updates.add("document_styles = :documentStyles::jsonb")
            bindings["documentStyles"] = objectMapper.writeValueAsString(command.documentStyles)
        }

        if (command.clearPageSettings) {
            updates.add("page_settings = NULL")
        } else if (command.pageSettings != null) {
            updates.add("page_settings = :pageSettings::jsonb")
            bindings["pageSettings"] = objectMapper.writeValueAsString(command.pageSettings)
        }

        if (command.clearBlockStylePresets) {
            updates.add("block_style_presets = NULL")
        } else if (command.blockStylePresets != null) {
            updates.add("block_style_presets = :blockStylePresets::jsonb")
            bindings["blockStylePresets"] = objectMapper.writeValueAsString(command.blockStylePresets)
        } else if (command.mergeBlockStylePresets != null || command.removeBlockStylePresets.isNotEmpty()) {
            // jsonb `||` replaces top-level keys (whole presets); `-` drops the named ones.
            updates.add(
                "block_style_presets = (COALESCE(block_style_presets, '{}'::jsonb) || :mergePresets::jsonb) - :removePresets::text[]",
            )
            bindings["mergePresets"] = objectMapper.writeValueAsString(command.mergeBlockStylePresets ?: BlockStylePresets.EMPTY)
            bindings["removePresets"] = command.removeBlockStylePresets.toTypedArray()
        }

        if (command.clearSpacingUnit) {
            updates.add("spacing_unit = NULL")
        } else if (command.spacingUnit != null) {
            updates.add("spacing_unit = :spacingUnit")
            bindings["spacingUnit"] = command.spacingUnit
        }

        if (updates.isEmpty()) {
            // No updates to apply, return existing theme
            return getExisting(command.id)?.also { Revisions.refuse("Theme '${command.id.key.value}'", command.expectedRevision, it.revision) }
        }

        updates.add("updated_at = NOW()")
        updates.add("updated_by = :updatedBy")
        bindings["updatedBy"] = currentUserIdOrNull()?.value

        val sql = """
            UPDATE themes
            SET ${updates.joinToString(", ")}
            WHERE id = :id
              AND tenant_key = :tenantId
              AND version_id = ${versionAt("tenantId", "catalogKey")}
              AND ${Revisions.MATCHES}
            RETURNING *, (SELECT cat.catalog_key FROM catalogs cat WHERE cat.tenant_key = themes.tenant_key AND cat.catalog_id = themes.catalog_id) AS catalog_key, (SELECT cat.namespace FROM catalogs cat WHERE cat.tenant_key = themes.tenant_key AND cat.catalog_id = themes.catalog_id) AS namespace
        """

        return jdbi.withHandle<Theme?, Exception> { handle ->
            val query = handle.createQuery(sql)
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bindCatalog("catalogKey", command.id.catalogId)

            bindings.forEach { (key, value) -> query.bind(key, value) }
            query.bind("expectedRevision", command.expectedRevision)

            query.mapTo<Theme>()
                .findOne()
                .orElse(null)
                ?: null.also {
                    Revisions.refuse("Theme '${command.id.key.value}'", command.expectedRevision, handle.contentRevision("themes", "id", command.id.catalogId, command.id.key))
                }
        }
    }

    private fun getExisting(id: ThemeId): Theme? = jdbi.withHandle<Theme?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT t.*, cat.catalog_key AS catalog_key, cat.namespace AS namespace
            FROM themes t
            JOIN catalogs cat ON cat.tenant_key = t.tenant_key AND cat.catalog_id = t.catalog_id
            WHERE t.id = :id
              AND t.tenant_key = :tenantId
              AND t.version_id = ${versionAt("tenantId", "catalogKey")}
            """,
        )
            .bind("id", id.key)
            .bind("tenantId", id.tenantKey)
            .bindCatalog("catalogKey", id.catalogId)
            .mapTo<Theme>()
            .findOne()
            .orElse(null)
    }
}
