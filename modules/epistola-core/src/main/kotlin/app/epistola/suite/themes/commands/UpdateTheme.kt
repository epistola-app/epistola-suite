// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.commands

import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.revisions.DraftResources
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
import app.epistola.suite.themes.ThemeFields
import app.epistola.suite.themes.ThemeReads
import app.epistola.suite.themes.ThemeStore
import app.epistola.suite.themes.crossCatalogRefs
import app.epistola.suite.themes.fields
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
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
    /** The baseline margins of every flow block (`marginTop`/`marginBottom`); null keeps them. */
    val defaultBlockStyles: Map<String, Any?>? = null,
    val clearDefaultBlockStyles: Boolean = false,
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
        validate("defaultBlockStyles", defaultBlockStyles.orEmpty().keys.all { it == "marginTop" || it == "marginBottom" }) { "A theme's default block styles set marginTop and marginBottom only" }
    }
}

@Component
class UpdateThemeHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val usedCatalogs: UsedCatalogDeclarer,
    private val themes: ThemeStore,
) : CommandHandler<UpdateTheme, Theme?> {
    override fun handle(command: UpdateTheme): Theme? {
        val saved = save(command)
        // What the saved resource uses of other catalogs is declared now, so the draft renders and releases with it.
        saved?.let { usedCatalogs.declare(command.id.catalogId, it.crossCatalogRefs(objectMapper)) }
        return saved
    }

    private fun save(command: UpdateTheme): Theme? {
        requireCatalogEditable(command.id.catalogId)
        val catalog = command.id.catalogId
        return jdbi.withHandle<Theme?, Exception> { handle ->
            // Locked, so the change is applied to what is there, and two writers do not race.
            val revision = DraftResources.lockRevision(handle, catalog, ThemeStore.THEME, command.id.key.value) ?: return@withHandle null
            Revisions.refuse("Theme '${command.id.key.value}'", command.expectedRevision, revision)
            val current = checkNotNull(ThemeReads.get(handle, objectMapper, catalog, command.id.key)) { "Theme ${command.id.key.value} is locked, so it is there" }
            if (!command.changesSomething()) return@withHandle current
            themes.save(handle, current, command.applyTo(current.fields()), currentUserIdOrNull()?.value)
        }
    }

    private fun UpdateTheme.changesSomething(): Boolean = name != null || clearDescription || description != null || documentStyles != null ||
        clearPageSettings || pageSettings != null || clearBlockStylePresets || blockStylePresets != null ||
        mergeBlockStylePresets != null || removeBlockStylePresets.isNotEmpty() || clearSpacingUnit || spacingUnit != null ||
        clearDefaultBlockStyles || defaultBlockStyles != null

    /** [current] with this update's changes: only the fields it names, as a partial update. */
    private fun UpdateTheme.applyTo(current: ThemeFields): ThemeFields = current.copy(
        name = name ?: current.name,
        // Blank removes it, as clearDescription does (as UpdateDocumentTemplate's description).
        description = when {
            clearDescription || description?.isBlank() == true -> null
            description != null -> description
            else -> current.description
        },
        documentStyles = documentStyles ?: current.documentStyles,
        pageSettings = if (clearPageSettings) null else pageSettings ?: current.pageSettings,
        blockStylePresets = when {
            clearBlockStylePresets -> null

            blockStylePresets != null -> blockStylePresets

            // Per preset: a merged one replaces the preset of its name whole; a removed one goes.
            mergeBlockStylePresets != null || removeBlockStylePresets.isNotEmpty() ->
                BlockStylePresets(current.blockStylePresets.orEmpty() + mergeBlockStylePresets.orEmpty() - removeBlockStylePresets)

            else -> current.blockStylePresets
        },
        spacingUnit = if (clearSpacingUnit) null else spacingUnit ?: current.spacingUnit,
        defaultBlockStyles = if (clearDefaultBlockStyles) null else defaultBlockStyles ?: current.defaultBlockStyles,
    )
}
