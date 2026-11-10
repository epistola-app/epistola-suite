// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.queries.IsCatalogEditable
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext

/**
 * Whether a picker in version [version] of [catalog] offers other catalogs' resources apart, to be
 * declared before one is used (`Reach.UNDECLARED`): only where declaring one can land. The viewer
 * may change which catalogs a draft uses (`TEMPLATE_EDIT`), the catalog can be changed here, and the
 * version is not a release, which never changes. Otherwise the picker offers what the version can
 * use and nothing it could not go on to use.
 */
internal fun offersDeclaring(catalog: CatalogId, version: CatalogReleaseRef?): Boolean = version?.takeIf { it.catalog == catalog }?.draft !is DraftRef.Released &&
    SecurityContext.current().hasPermission(catalog.tenantKey, Permission.TEMPLATE_EDIT) &&
    IsCatalogEditable(catalog).query()

/** Where a picker in catalog version [segment] declares another catalog before one of its resources is used. */
internal fun declareUrl(tenantKey: app.epistola.suite.common.ids.TenantKey, segment: app.epistola.suite.htmx.CatalogSegment): String = "/tenants/${tenantKey.value}/catalogs/${segment.value}/dependencies/declare"

/**
 * The theme picker of version [version] of [catalog] (in [segment]): the themes it can use, by
 * catalog, and, where declaring can land ([offersDeclaring]), those of the catalogs it could declare,
 * by catalog, with where declaring one goes. [declareUrl] is null when none are offered.
 */
internal data class ThemePicker(
    val themes: List<app.epistola.suite.themes.Theme>,
    val themeCatalogs: Map<String, List<app.epistola.suite.themes.Theme>>,
    val otherThemeCatalogs: Map<String, List<app.epistola.suite.themes.Theme>>,
    val declareUrl: String?,
) {
    companion object {
        fun of(catalog: CatalogId, version: CatalogReleaseRef?, segment: app.epistola.suite.htmx.CatalogSegment): ThemePicker {
            val themes = app.epistola.suite.themes.queries.ListVisibleThemes(catalog, version).query()
            val offered = offersDeclaring(catalog, version)
            val others = if (offered) app.epistola.suite.themes.queries.ListVisibleThemes(catalog, version, app.epistola.suite.catalog.dependencies.Reach.UNDECLARED).query() else emptyList()
            return ThemePicker(
                themes = themes,
                themeCatalogs = themes.groupBy { it.catalogKey.value },
                otherThemeCatalogs = others.groupBy { it.catalogKey.value },
                declareUrl = if (offered && others.isNotEmpty()) declareUrl(catalog.tenantKey, segment) else null,
            )
        }
    }
}

/**
 * An attribute's code-list picker in catalog version [segment]: the code lists it can use, and, where
 * declaring can land ([offersDeclaring]), those of the catalogs it could declare, with where declaring
 * one goes ([declareUrl], null when none are offered). Without a catalog (a dialog opened outside
 * one), every catalog's, as before.
 */
internal data class CodeListPicker(
    val codeLists: List<app.epistola.suite.attributes.codelists.model.CodeList>,
    val otherCodeLists: List<app.epistola.suite.attributes.codelists.model.CodeList>,
    val declareUrl: String?,
) {
    companion object {
        fun of(tenantId: app.epistola.suite.common.ids.TenantId, segment: app.epistola.suite.htmx.CatalogSegment?): CodeListPicker {
            if (segment == null) return CodeListPicker(app.epistola.suite.attributes.codelists.queries.ListCodeLists(tenantId, catalogVersion = null).query(), emptyList(), null)
            val catalog = segment.catalogIn(tenantId)
            val version = segment.ref(tenantId.key)
            val usable = app.epistola.suite.attributes.codelists.queries.ListVisibleCodeLists(catalog, version).query()
            val offered = offersDeclaring(catalog, version)
            val others = if (offered) app.epistola.suite.attributes.codelists.queries.ListVisibleCodeLists(catalog, version, app.epistola.suite.catalog.dependencies.Reach.UNDECLARED).query() else emptyList()
            return CodeListPicker(usable, others, if (offered && others.isNotEmpty()) declareUrl(tenantId.key, segment) else null)
        }
    }
}

/** The segment version [version] of [catalog] is addressed by: its draft or release, else the catalog itself. */
internal fun segmentOf(catalog: CatalogId, version: CatalogReleaseRef?): app.epistola.suite.htmx.CatalogSegment = app.epistola.suite.htmx.CatalogSegment(catalog.key, version?.takeIf { it.catalog == catalog }?.draft?.toString(), catalog.namespace)

/**
 * A variant's attribute picker in version [version] of a template's catalog: the attributes it can
 * carry ([descriptors]), and, where declaring can land ([offersDeclaring]), those of the catalogs it
 * could declare ([others], each naming the catalog it declares), with where declaring one goes
 * ([declareUrl], null when none are offered). [rows] is every attribute a form keeps a row for.
 */
internal data class AttributePicker(
    val descriptors: List<AttributeDescriptor>,
    val others: List<AttributeDescriptor>,
    val declareUrl: String?,
) {
    val rows: List<AttributeDescriptor> get() = descriptors + others

    companion object {
        fun of(catalog: CatalogId, version: CatalogReleaseRef?, definitions: List<app.epistola.suite.attributes.model.VariantAttributeDefinition>): AttributePicker {
            val offered = offersDeclaring(catalog, version)
            val others = if (offered) {
                app.epistola.suite.attributes.queries.ListVisibleAttributeDefinitions(catalog, version, app.epistola.suite.catalog.dependencies.Reach.UNDECLARED).query().map { definition ->
                    buildAttributeDescriptors(listOf(definition)).single().copy(declares = definition.catalogId.address, declaresName = definition.catalogKey.value)
                }
            } else {
                emptyList()
            }
            return AttributePicker(buildAttributeDescriptors(definitions), others, if (others.isNotEmpty()) declareUrl(catalog.tenantKey, segmentOf(catalog, version)) else null)
        }
    }
}
