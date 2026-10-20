// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.queries.BrowseResource
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.security.Permission

/**
 * What a version of an authored catalog holds, grouped by kind as people think of it — templates
 * first — with a way to add each kind there (docs/catalog-workspace-navigation.md). Shown on the
 * catalog page for the next version and on every draft's page.
 *
 * Every kind is listed, even when empty, so the catalog page is also where something new is made.
 * Each "New" opens that kind's own create dialog on this catalog version; it is offered when the
 * viewer may create that kind, and otherwise the group says so.
 */
data class CatalogContents(val groups: List<Group>) {

    /** One kind of resource: what there is, and how to add one. */
    data class Group(
        val type: String,
        val label: String,
        val newLabel: String,
        /** The create dialog for this kind, on this catalog version. */
        val newHref: String,
        val mayCreate: Boolean,
        val items: List<Item>,
        /** The list of this kind, inside the catalog at this version. */
        val listHref: String = "",
    ) {
        /** How many of this kind there are. */
        val count: Int get() = items.size

        /** How many of them are new or changed since the last published version. */
        val changedCount: Int get() = items.count { it.change != null }

        /** The create control is withheld because the viewer may not create this kind; the group says so. */
        val createWithheld: Boolean get() = !mayCreate
    }

    /**
     * One resource, linked to where it is edited. [change] says how it differs from the last
     * published version, in people's words — "New" or "Changed" — and is null when it does not.
     */
    data class Item(val type: String, val slug: String, val name: String, val description: String?, val href: String, val change: String? = null)

    /** Nothing at all yet, for the empty state. */
    val isEmpty: Boolean get() = groups.all { it.items.isEmpty() }

    /** The templates: what people make documents from, so the page lists them first and on their own. */
    val templates: Group get() = groups.first { it.type == "template" }

    /** Everything else a template is built from, which the page shows below the templates. */
    val parts: CatalogContents get() = CatalogContents(groups.filter { it.type != "template" })

    /** The kinds that hold something, each shown as a row. */
    val filled: List<Group> get() = groups.filter { it.items.isNotEmpty() }

    /** The empty kinds the viewer may add, offered on one line rather than a box each. */
    val addable: List<Group> get() = groups.filter { it.items.isEmpty() && it.mayCreate }

    /** The kinds the viewer may not add, lower case, for the one sentence that says so. */
    val withheldLabels: List<String> get() = groups.filter { it.createWithheld }.map { it.label.lowercase() }

    companion object {
        private data class Kind(
            val type: String,
            val label: String,
            val newLabel: String,
            val area: String,
            val permission: Permission,
            /** Whether a resource of this kind has a page of its own, `/{area}/{segment}/{slug}`; else the list filtered to the catalog. */
            val ownPage: Boolean,
        )

        /** In the order people reach for them: what makes a document first. */
        private val KINDS = listOf(
            Kind("template", "Templates", "New template", "templates", Permission.TEMPLATE_EDIT, ownPage = true),
            Kind("stencil", "Stencils", "New stencil", "stencils", Permission.STENCIL_EDIT, ownPage = true),
            Kind("theme", "Themes", "New theme", "themes", Permission.THEME_EDIT, ownPage = true),
            Kind("image", "Images", "Upload image", "images", Permission.TEMPLATE_EDIT, ownPage = false),
            Kind("font", "Fonts", "Add font", "fonts", Permission.REFERENCE_EDIT, ownPage = false),
            Kind("attribute", "Attributes", "New attribute", "attributes", Permission.TENANT_SETTINGS, ownPage = false),
            Kind("codeList", "Code lists", "New code list", "code-lists", Permission.TENANT_SETTINGS, ownPage = true),
        )

        /**
         * The contents of the catalog version at [segment], from [resources]; [may] says whether the
         * viewer holds a permission. Resources of a kind not listed above are left out.
         */
        fun of(
            tenantKey: TenantKey,
            segment: CatalogSegment,
            resources: List<BrowseResource>,
            /** How each resource differs from the last published version, by `type/slug`. */
            changes: Map<String, CatalogResourceState> = emptyMap(),
            may: (Permission) -> Boolean,
        ): CatalogContents {
            val base = "/tenants/${tenantKey.value}"
            val catalog = segment.value
            return CatalogContents(
                KINDS.map { kind ->
                    Group(
                        type = kind.type,
                        label = kind.label,
                        newLabel = kind.newLabel,
                        newHref = "$base/catalogs/$catalog/${kind.area}/new",
                        listHref = "$base/catalogs/$catalog/${kind.area}",
                        mayCreate = may(kind.permission),
                        items = resources.filter { it.type == kind.type }.sortedBy { it.name.lowercase() }.map { resource ->
                            Item(
                                type = resource.type,
                                slug = resource.slug,
                                name = resource.name,
                                description = resource.description,
                                href = if (kind.ownPage) "$base/catalogs/$catalog/${kind.area}/${resource.slug}" else "$base/catalogs/$catalog/${kind.area}",
                                change = when (changes["${resource.type}/${resource.slug}"]) {
                                    CatalogResourceState.NEW -> "New"
                                    CatalogResourceState.MODIFIED -> "Changed"
                                    else -> null
                                },
                            )
                        },
                    )
                },
            )
        }
    }
}
