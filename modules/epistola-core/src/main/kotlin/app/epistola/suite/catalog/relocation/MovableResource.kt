// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ReferenceSiteKind

/**
 * The resource types relocation supports, and what moving one touches.
 *
 * A type becomes movable once its table is keyed by `(tenant_key, resource_id)` rather than by its
 * address, and no dependant stores its catalog — the recipe in
 * `docs/catalog-resource-identity-migration.md`. Until a type has been through that, moving it
 * would leave dependants pointing at an address it no longer occupies, so it is deliberately
 * absent here and the planner reports it as unsupported.
 *
 * Adding an entry is therefore the *last* step of making a type movable, not the first.
 */
enum class MovableResource(
    val type: CatalogResourceType,
    /** Table whose address the move updates. Never interpolated from caller input. */
    val table: String,
    /** Column on [table] holding the resource key, so a relocation can rename as well as move. */
    val keyColumn: String,
    /** Content reference kinds that target this type and must be re-pointed at the destination. */
    val contentReferenceKinds: Set<ReferenceSiteKind>,
    /**
     * SQL type to cast the target key to, when [keyColumn] is not string-backed.
     *
     * Keys bind as text, which Postgres accepts for the varchar-backed key domains but not for a
     * UUID-backed one. Naming the domain here keeps that a property of the type rather than a
     * special case in the executor. Never interpolated from caller input.
     */
    val keyColumnType: String? = null,
) {
    /**
     * Referenced from template and stencil content. Published references to it keep their old
     * address and resolve through the alias; the only published bytes a move touches are the
     * relative references inside the moving stencil's own versions, pinned to the catalog it leaves.
     */
    STENCIL(
        CatalogResourceType.STENCIL,
        "stencils",
        "id",
        setOf(ReferenceSiteKind.STENCIL_INSERTION),
    ),

    /**
     * Referenced only by the keys of `template_variants.attributes`, which is live mutable
     * configuration rather than versioned content. Every reference to an attribute is therefore
     * rewritable and none has to survive on an alias — which is what makes this the simplest type
     * to move, and why it was the first re-keyed.
     */
    ATTRIBUTE(
        CatalogResourceType.ATTRIBUTE,
        "variant_attribute_definitions",
        "id",
        emptySet(),
    ),

    /**
     * Nothing references a template as a catalog dependency, so there is no content to re-point.
     * All of a template's coupling is downstream: its variants, versions, contract versions,
     * activations, quality findings and load-test runs follow it by `ON UPDATE CASCADE`, while
     * generation history deliberately does not — see `V20260905090400__core_template_relocation`.
     *
     * Unlike stencils and attributes this type is not yet re-keyed onto its identity; the address
     * still sits in those primary keys and the cascade does the work. That is the same interim
     * shape stencils are in, not the target model.
     */
    TEMPLATE(
        CatalogResourceType.TEMPLATE,
        "document_templates",
        "id",
        emptySet(),
    ),

    /**
     * Referenced by a relational binding from `variant_attribute_definitions` rather than from
     * versioned content, so nothing has to be rewritten: the binding and the owned entries follow
     * by `ON UPDATE CASCADE` — see `V20260905090600__core_code_list_relocation`. That is also why
     * moving one cannot break a published document: no payload names a code list.
     */
    CODE_LIST(
        CatalogResourceType.CODE_LIST,
        "code_lists",
        "slug",
        emptySet(),
    ),

    /**
     * Resolved at render time, so moving one could have left a published document unable to load
     * its image. `GetAssetContent` follows the alias when a qualified reference misses, which is
     * what makes this safe; an unqualified reference resolves by id alone and never noticed.
     *
     * References are not rewritten: `IMAGE_ASSET` resolves tenant-globally rather than relative to
     * the containing catalog, so a stored reference means the same thing wherever its owner lives.
     */
    ASSET(
        CatalogResourceType.ASSET,
        "assets",
        "id",
        emptySet(),
        // assets.id is the UUID-backed ASSET_KEY domain, unlike every other key here.
        keyColumnType = "ASSET_KEY",
    ),

    /**
     * Also resolved at render time, through `ResolveFontFace`, which follows the alias when the
     * family is not at the address the content names — otherwise a moved family would silently
     * render as the built-in fallback rather than failing.
     *
     * Its faces follow by `ON UPDATE CASCADE`. A face's backing asset does not: it has its own
     * `asset_catalog_key` since `V20260905090700`, so a font and its asset move independently.
     */
    FONT(
        CatalogResourceType.FONT,
        "fonts",
        "slug",
        setOf(ReferenceSiteKind.FONT_FAMILY),
    ),
    ;

    companion object {
        fun of(type: CatalogResourceType): MovableResource? = entries.firstOrNull { it.type == type }
    }
}
