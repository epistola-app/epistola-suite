// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** A theme a catalog version names as its default: its key and the catalog it lives in. */
data class CatalogDefaultTheme(val themeKey: ThemeKey, val catalogKey: CatalogKey)

/** A block style preset a catalog version declares (contract ADR 0007); its themes style it. */
data class CatalogPreset(val key: String, val name: String, val description: String? = null) {
    init {
        validate("presets", PRESET_KEY.matches(key) && key.length <= MAX_KEY_LENGTH) { "Preset key '$key' must be lowercase letters, digits and dashes, at most $MAX_KEY_LENGTH characters" }
        validate("presets", name.isNotBlank() && name.length <= 100) { "Preset '$key' needs a name of at most 100 characters" }
        validate("presets", (description?.length ?: 0) <= 500) { "The description of preset '$key' is longer than 500 characters" }
    }

    companion object {
        /** The key rule for presets: what themes already use as preset keys. */
        val PRESET_KEY = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")

        /** The contract's limit (`CatalogPresets.KEY_MAX_LENGTH`). */
        const val MAX_KEY_LENGTH = 50
    }
}

/** A catalog version's theme settings: the theme its inheriting templates render with, and its presets. */
data class CatalogThemeSettings(
    /** The theme a template that names none renders with; null renders with the rendering defaults alone. */
    val defaultTheme: CatalogDefaultTheme?,
    /** The presets the version declares, by key. */
    val presets: List<CatalogPreset>,
)

/**
 * The theme settings of the version of [catalogKey] a caller means (contract ADR 0007): the current
 * one, or [catalogVersion].
 */
data class GetCatalogThemeSettings(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    override val catalogVersion: CatalogReleaseRef? = null,
) : Query<CatalogThemeSettings>,
    CatalogReleaseScoped,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetCatalogThemeSettingsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetCatalogThemeSettings, CatalogThemeSettings> {
    override fun handle(query: GetCatalogThemeSettings): CatalogThemeSettings = jdbi.withHandle<CatalogThemeSettings, Exception> { handle ->
        handle.createQuery(
            """
            SELECT r.resource_key AS theme_key, tc.catalog_key AS theme_catalog_key, v.presets::text AS presets
            FROM catalog_versions v
            LEFT JOIN catalog_resources r ON r.tenant_key = v.tenant_key AND r.resource_id = v.default_theme_resource_id
            LEFT JOIN catalogs tc ON tc.tenant_key = r.tenant_key AND tc.catalog_id = r.catalog_id
            WHERE v.tenant_key = :t AND v.version_id = ${versionAt("t", "c")}
            """,
        )
            .bind("t", query.tenantKey)
            .bind("c", query.catalogKey)
            .map { rs, _ ->
                val themeKey = rs.getString("theme_key")
                CatalogThemeSettings(
                    defaultTheme = themeKey?.let { CatalogDefaultTheme(ThemeKey.of(it), CatalogKey.of(rs.getString("theme_catalog_key"))) },
                    presets = presetsOf(objectMapper, rs.getString("presets")),
                )
            }
            .findOne()
            .orElse(CatalogThemeSettings(defaultTheme = null, presets = emptyList()))
    }
}

/** The presets stored as `[{key, name, description}]`. */
internal fun presetsOf(objectMapper: ObjectMapper, json: String?): List<CatalogPreset> {
    if (json.isNullOrBlank()) return emptyList()
    return objectMapper.readTree(json).iterator().asSequence().toList().map { node ->
        CatalogPreset(
            key = node.get("key").asString(),
            name = node.get("name")?.takeUnless { it.isNull }?.asString() ?: node.get("key").asString(),
            description = node.get("description")?.takeUnless { it.isNull }?.asString(),
        )
    }
}

/**
 * Sets the default theme of the version of [catalogKey] a caller means (contract ADR 0007): the theme a
 * template that names none renders with. [theme] may live in the catalog itself or in another one,
 * which the catalog then declares it uses; null clears it.
 */
data class SetCatalogDefaultTheme(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val theme: CatalogDefaultTheme?,
    override val catalogVersion: CatalogReleaseRef? = null,
) : Command<CatalogThemeSettings>,
    CatalogReleaseScoped,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT
}

@Component
class SetCatalogDefaultThemeHandler(
    private val jdbi: Jdbi,
    private val usedCatalogs: UsedCatalogDeclarer,
) : CommandHandler<SetCatalogDefaultTheme, CatalogThemeSettings> {
    override fun handle(command: SetCatalogDefaultTheme): CatalogThemeSettings {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        jdbi.useHandle<Exception> { handle ->
            val resourceId = command.theme?.let { theme ->
                handle.createQuery(
                    "SELECT resource_id FROM themes WHERE tenant_key = :t AND version_id = ${versionAt("t", "themeCatalog")} AND id = :theme",
                )
                    .bind("t", command.tenantKey)
                    .bind("themeCatalog", theme.catalogKey)
                    .bind("theme", theme.themeKey)
                    .mapTo(java.util.UUID::class.java)
                    .findOne()
                    .orElse(null)
                    ?: throw ValidationException("defaultTheme", "Theme '${theme.catalogKey.value}/${theme.themeKey.value}' does not exist")
            }
            handle.createUpdate("UPDATE catalog_versions SET default_theme_resource_id = :theme WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")}")
                .bind("t", command.tenantKey)
                .bind("c", command.catalogKey)
                .bind("theme", resourceId)
                .execute()
        }
        // A theme of another catalog is a use of it, declared as any reference is.
        command.theme?.catalogKey?.takeIf { it != command.catalogKey }?.let { usedCatalogs.declare(command.tenantKey, command.catalogKey, it) }
        return GetCatalogThemeSettings(command.tenantKey, command.catalogKey, command.catalogVersion).query()
    }
}

/**
 * Sets the presets the version of [catalogKey] a caller means declares (contract ADR 0007), replacing
 * them. Keys are unique.
 */
data class SetCatalogPresets(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val presets: List<CatalogPreset>,
    override val catalogVersion: CatalogReleaseRef? = null,
) : Command<CatalogThemeSettings>,
    CatalogReleaseScoped,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT

    init {
        val duplicates = presets.groupBy { it.key }.filterValues { it.size > 1 }.keys
        validate("presets", duplicates.isEmpty()) { "Preset keys must be unique: ${duplicates.joinToString(", ")}" }
    }
}

@Component
class SetCatalogPresetsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<SetCatalogPresets, CatalogThemeSettings> {
    override fun handle(command: SetCatalogPresets): CatalogThemeSettings {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        val json = objectMapper.writeValueAsString(command.presets.sortedBy { it.key }.map { mapOf("key" to it.key, "name" to it.name, "description" to it.description) })
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("UPDATE catalog_versions SET presets = CAST(:presets AS JSONB) WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")}")
                .bind("t", command.tenantKey)
                .bind("c", command.catalogKey)
                .bind("presets", json)
                .execute()
        }
        return GetCatalogThemeSettings(command.tenantKey, command.catalogKey, command.catalogVersion).query()
    }
}

/** A preset the content of a catalog may use (contract ADR 0007), and the catalog that declares it. */
data class AvailablePreset(val key: String, val name: String, val description: String?, val catalogKey: CatalogKey)

/**
 * The presets the content of [catalogKey] may use (contract ADR 0007): the ones its version declares,
 * then those its dependencies declare, each key once (the catalog's own first). Empty when none of them
 * declares any; the editor then falls back to the presets its theme styles.
 */
data class GetAvailablePresets(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    override val catalogVersion: CatalogReleaseRef? = null,
) : Query<List<AvailablePreset>>,
    CatalogReleaseScoped,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetAvailablePresetsHandler : QueryHandler<GetAvailablePresets, List<AvailablePreset>> {
    override fun handle(query: GetAvailablePresets): List<AvailablePreset> {
        val own = GetCatalogThemeSettings(query.tenantKey, query.catalogKey, query.catalogVersion).query().presets
            .map { AvailablePreset(it.key, it.name, it.description, query.catalogKey) }
        val fromDependencies = app.epistola.suite.catalog.queries.ListCatalogDependencies(query.tenantKey, query.catalogKey, query.catalogVersion).query()
            .map { CatalogKey.of(it.catalogKey) }
            .distinct()
            .flatMap { dependency -> GetCatalogThemeSettings(query.tenantKey, dependency).query().presets.map { AvailablePreset(it.key, it.name, it.description, dependency) } }
        return (own + fromDependencies).distinctBy { it.key }
    }
}

/**
 * The block style presets a template or stencil may name (contract ADR 0007): once its catalog declares
 * presets, a block naming one that is not among the available presets is refused when saved. A catalog
 * that declares none is not checked, so older content and imported archives keep working.
 */
object PresetUse {
    fun requireAvailable(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        document: app.epistola.suite.templates.model.TemplateDocument,
        catalogVersion: CatalogReleaseRef? = null,
    ) {
        val named = document.nodes.values.mapNotNull { it.stylePreset?.takeIf(String::isNotBlank) }.toSet()
        if (named.isEmpty()) return
        val available = GetAvailablePresets(tenantKey, catalogKey, catalogVersion).query().map { it.key }.toSet()
        if (available.isEmpty()) return
        val unknown = (named - available).sorted()
        if (unknown.isNotEmpty()) {
            throw ValidationException(
                "templateModel",
                "Preset ${unknown.joinToString(", ") { "'$it'" }} is not one of this catalog's presets. Declare it on the catalog's Details page, or choose another.",
                app.epistola.suite.validation.ValidationCode.TEMPLATE_STYLE_PRESET_UNKNOWN,
            )
        }
    }
}

/**
 * The theme settings release [version] of [catalogKey] was published with (contract ADR 0007), read
 * from what it froze; empty settings for a release published before catalogs had them.
 */
data class GetReleasedThemeSettings(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Query<CatalogThemeSettings>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetReleasedThemeSettingsHandler(
    private val assembler: app.epistola.suite.catalog.revisions.ReleaseContentAssembler,
) : QueryHandler<GetReleasedThemeSettings, CatalogThemeSettings> {
    override fun handle(query: GetReleasedThemeSettings): CatalogThemeSettings {
        val info = assembler.readCatalogInfo(query.tenantKey, query.catalogKey, query.version)
            ?: return CatalogThemeSettings(defaultTheme = null, presets = emptyList())
        return CatalogThemeSettings(
            defaultTheme = info.defaultTheme?.let { CatalogDefaultTheme(ThemeKey.of(it.key), it.catalogKey?.let(CatalogKey::of) ?: query.catalogKey) },
            presets = info.presets.map { CatalogPreset(it.key, it.name, it.description) },
        )
    }
}
