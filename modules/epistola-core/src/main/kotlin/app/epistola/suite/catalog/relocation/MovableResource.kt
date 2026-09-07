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
    /**
     * Whether a relocation may change this type's key.
     *
     * False where the key is a generated identifier rather than a name. Renaming one is not a
     * rename in any useful sense, and it breaks the references that resolve by that identifier
     * alone -- an unqualified image reference carries no catalog, so nothing is left to find the
     * alias with.
     */
    val renameable: Boolean = true,
) {
    /**
     * Referenced from template and stencil content. Published references to it keep their old
     * address and resolve through the alias; the only published bytes a move touches are the
     * relative references inside the moving stencil's own versions, pinned to the catalog it leaves.
     *
     * Keyed by identity (`V20260905090900`), as are its versions: they name the stencil rather
     * than carrying a copy of its address, so a move updates one row.
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
     * activations, quality findings and load-test runs name its identity and so follow it without
     * a cascade, while generation history deliberately does not -- it keeps the address recorded
     * at the time, see `V20260905090400__core_template_relocation`.
     *
     * The largest of the seven: the address sat in eight tables' primary keys across three
     * modules, so re-keying it is what let every one of relocation's weakened foreign keys go.
     */
    TEMPLATE(
        CatalogResourceType.TEMPLATE,
        "document_templates",
        "id",
        emptySet(),
    ),

    /**
     * Keyed by identity (`V20260905090600`), so its entries and the attributes bound to it
     * reference the code list itself rather than where it happens to live: a move or a rename
     * updates one row and nothing cascades or is rewritten. Queries that need the address read it
     * from `code_lists`, which keeps the public shape unchanged. No payload names a code list, so
     * no published document can break either way.
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
     * Keyed by identity (`V20260905090700`), so a font face pointing at one is undisturbed by a
     * move.
     */
    ASSET(
        CatalogResourceType.ASSET,
        "assets",
        "id",
        emptySet(),
        // assets.id is the UUID-backed ASSET_KEY domain, unlike every other key here.
        keyColumnType = "ASSET_KEY",
        renameable = false,
    ),

    /**
     * Also resolved at render time, through `ResolveFontFace`, which follows the alias when the
     * family is not at the address the content names — otherwise a moved family would silently
     * render as the built-in fallback rather than failing.
     *
     * Keyed by identity (`V20260905090700`), as are its faces: a face names its family and its
     * backing asset by identity rather than through a shared catalog column, so a font and its
     * asset move independently and neither move touches a face.
     */
    FONT(
        CatalogResourceType.FONT,
        "fonts",
        "slug",
        setOf(ReferenceSiteKind.FONT_FAMILY),
    ),

    /**
     * The only type referenced three different ways at once: from content (`themeRef`), from a
     * template's own binding, and from the tenant-wide default. Keyed by identity
     * (`V20260905090800`), so the two relational ones name the theme itself and need neither a
     * cascade nor a rewrite; content references are rewritten in drafts and resolved through the
     * alias in published versions, exactly as stencil references are.
     *
     * Live resolution runs through `ThemeStyleResolver`, which follows the alias — without it a
     * template would quietly fall back to the tenant default theme instead of the one it names.
     * Published versions carry a frozen `resolved_theme` snapshot and are unaffected either way.
     */
    THEME(
        CatalogResourceType.THEME,
        "themes",
        "id",
        setOf(ReferenceSiteKind.THEME_OVERRIDE),
    ),
    ;

    companion object {
        fun of(type: CatalogResourceType): MovableResource? = entries.firstOrNull { it.type == type }
    }
}
