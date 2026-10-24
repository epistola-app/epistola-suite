// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** What a theme says: everything its content holds but its key. */
data class ThemeFields(
    val name: String,
    val description: String?,
    val documentStyles: DocumentStyles,
    val pageSettings: PageSettings?,
    val blockStylePresets: BlockStylePresets?,
    val spacingUnit: Float?,
)

/** What [this] theme says. */
fun Theme.fields(): ThemeFields = ThemeFields(name, description, documentStyles, pageSettings, blockStylePresets, spacingUnit)

/**
 * Writes the themes of a draft (ADR 0033): its content, the theme's wire form (`ThemeResource`), to
 * `resource_contents`, and the draft's row naming it ([DraftResources]). Read back through
 * [ThemeReads], which maps the same JSON for a draft and a release.
 */
@Component
class ThemeStore(
    private val objectMapper: ObjectMapper,
    private val contents: ResourceContentStore,
) {

    /**
     * The content of theme [key]: its wire form, as a release stores it too. The spacing unit keeps
     * the two decimals it always had (the `NUMERIC(6, 2)` it was stored in before).
     */
    fun payload(key: String, fields: ThemeFields): JsonNode = objectMapper.valueToTree(
        ThemeResource(
            key = key,
            name = fields.name,
            description = fields.description,
            documentStyles = fields.documentStyles,
            pageSettings = fields.pageSettings,
            blockStylePresets = fields.blockStylePresets?.toMap(),
            spacingUnit = fields.spacingUnit?.let(::stored),
        ),
    )

    /** The spacing unit as stored: two decimals. */
    private fun stored(spacingUnit: Float): Float = BigDecimal(spacingUnit.toString()).setScale(2, RoundingMode.HALF_UP).toFloat()

    /** Stores [fields] as theme [key] of [catalog]'s tenant and returns its content hash. */
    fun store(handle: Handle, catalog: CatalogId, key: String, fields: ThemeFields): String = contents.store(handle, catalog.tenantKey, THEME, payload(key, fields))

    /** Adds theme [id] to the targeted draft; a theme already there is a unique violation. */
    fun create(handle: Handle, id: ThemeId, fields: ThemeFields, actor: UUID?): Theme {
        DraftResources.insert(handle, id.catalogId, THEME, id.key.value, store(handle, id.catalogId, id.key.value, fields), fields.name, fields.description, actor)
        return checkNotNull(ThemeReads.get(handle, objectMapper, id.catalogId, id.key)) { "Theme ${id.key.value} was just written" }
    }

    /**
     * Saves [fields] as theme [current] of the targeted draft, which the caller read (and locked)
     * in this transaction; null when the draft no longer holds it.
     */
    fun save(handle: Handle, current: Theme, fields: ThemeFields, actor: UUID?): Theme? {
        val id = ThemeId(current.id, current.catalogId)
        val hash = store(handle, id.catalogId, id.key.value, fields)
        val updated = DraftResources.update(handle, id.catalogId, THEME, id.key.value, hash, fields.name, fields.description, actor) ?: return null
        // What it says now, without reading it back: the content just written, the row's new bookkeeping.
        return current.copy(
            name = fields.name,
            description = fields.description,
            documentStyles = fields.documentStyles,
            pageSettings = fields.pageSettings,
            blockStylePresets = fields.blockStylePresets,
            spacingUnit = fields.spacingUnit?.let(::stored),
            revision = updated.revision,
            updatedAt = updated.updatedAt,
            updatedBy = actor?.let(app.epistola.suite.common.ids.UserKey::of),
        )
    }

    /** Creates or replaces theme [id] in the targeted draft; true when it was there already. */
    fun put(handle: Handle, id: ThemeId, fields: ThemeFields, actor: UUID?): Boolean {
        val hash = store(handle, id.catalogId, id.key.value, fields)
        if (DraftResources.update(handle, id.catalogId, THEME, id.key.value, hash, fields.name, fields.description, actor) != null) return true
        DraftResources.insert(handle, id.catalogId, THEME, id.key.value, hash, fields.name, fields.description, actor)
        return false
    }

    /** Removes theme [id] from the targeted draft; false when it held none. */
    fun delete(handle: Handle, id: ThemeId): Boolean = DraftResources.delete(handle, id.catalogId, THEME, id.key.value)

    /** Moves theme [key] of [source] to [targetKey] of [target], with its content renamed; false when there is none. */
    fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean {
        val theme = ThemeReads.get(handle, objectMapper, source, app.epistola.suite.common.ids.ThemeKey.of(key)) ?: return false
        return DraftResources.move(handle, source, THEME, key, target, targetKey, store(handle, source, targetKey, theme.fields()))
    }

    companion object {
        /** The resource type, and content kind, of a theme. */
        const val THEME = "theme"
    }
}
