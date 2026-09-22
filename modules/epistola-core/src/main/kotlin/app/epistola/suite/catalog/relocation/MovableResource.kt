// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ReferenceSiteKind
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.ThemeKey

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
     * Why a key cannot be held by this type, or null when it can -- the same rule its create command
     * enforces through the key's value class, with that class's own message. A relocation that
     * changes the key is checked against it in the preview, so an unusable key is a blocker rather
     * than a database error after approval.
     */
    val keyProblem: (String) -> String?,
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
     * alone -- an unqualified image reference carries no catalog, only the key.
     */
    val renameable: Boolean = true,
) {
    /**
     * Referenced from template and stencil content. Published references to it keep their old
     * address and stop resolving; the only published bytes a move touches are the relative
     * references inside the moving stencil's own versions, pinned to the catalog it leaves.
     *
     * Keyed by identity (`V20260905090100`), as are its versions: they name the stencil rather
     * than carrying a copy of its address, so a move updates one row.
     */
    STENCIL(
        CatalogResourceType.STENCIL,
        "stencils",
        "id",
        setOf(ReferenceSiteKind.STENCIL_INSERTION),
        { runCatching { StencilKey.of(it) }.exceptionOrNull()?.message },
    ),

    /**
     * Referenced only by the keys of `template_variants.attributes`, which is live mutable
     * configuration rather than versioned content. Every reference to an attribute is therefore
     * rewritable and none is left naming the old address — which is what makes this the simplest type
     * to move, and why it was the first re-keyed.
     */
    ATTRIBUTE(
        CatalogResourceType.ATTRIBUTE,
        "variant_attribute_definitions",
        "id",
        emptySet(),
        { runCatching { AttributeKey.of(it) }.exceptionOrNull()?.message },
    ),

    /**
     * Nothing references a template as a catalog dependency, so there is no content to re-point.
     * All of a template's coupling is downstream: its variants, versions, contract versions,
     * activations, quality findings and load-test runs name its identity and so follow it without
     * a cascade, while generation history deliberately does not -- it keeps the address recorded
     * at the time, see `V20260905090200__core_template_relocation`.
     *
     * The largest of the seven: the address sat in eight tables' primary keys across three
     * modules, so re-keying it is what let every one of relocation's weakened foreign keys go.
     */
    TEMPLATE(
        CatalogResourceType.TEMPLATE,
        "document_templates",
        "id",
        emptySet(),
        { runCatching { TemplateKey.of(it) }.exceptionOrNull()?.message },
    ),

    /**
     * Keyed by identity (`V20260905090100`), so its entries and the attributes bound to it
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
        { runCatching { CodeListKey.of(it) }.exceptionOrNull()?.message },
    ),

    /**
     * Resolved at render time: a published document whose reference names the catalog the image
     * left can no longer load it. An unqualified reference resolves by id alone and is unaffected.
     *
     * References are not rewritten: `IMAGE_ASSET` resolves tenant-globally rather than relative to
     * the containing catalog, so a stored reference means the same thing wherever its owner lives.
     * Keyed by identity (`V20260905090100`), so a font face pointing at one is undisturbed by a
     * move.
     */
    ASSET(
        CatalogResourceType.IMAGE,
        "assets",
        "id",
        emptySet(),
        { runCatching { AssetKey.of(it) }.exceptionOrNull()?.message },
        // Harmless since V20260911103003 made ASSET_KEY varchar-backed; it was a UUID domain before.
        keyColumnType = "ASSET_KEY",
        renameable = false,
    ),

    /**
     * Also resolved at render time, through `ResolveFontFace`: content that still names the
     * family's old address renders in the built-in fallback font.
     *
     * Keyed by identity (`V20260905090100`), as are its faces: a face names its family and its
     * backing asset by identity rather than through a shared catalog column, so a font and its
     * asset move independently and neither move touches a face.
     */
    FONT(
        CatalogResourceType.FONT,
        "fonts",
        "slug",
        setOf(ReferenceSiteKind.FONT_FAMILY),
        { runCatching { FontKey.of(it) }.exceptionOrNull()?.message },
    ),

    /**
     * The only type referenced three different ways at once: from content (`themeRef`), from a
     * template's own binding, and from the tenant-wide default. Keyed by identity
     * (`V20260905090100`), so the two relational ones name the theme itself and need neither a
     * cascade nor a rewrite; content references are rewritten in drafts and left naming the old
     * address in published versions, exactly as stencil references are.
     *
     * Live resolution runs through `ThemeStyleResolver`, which falls back to the tenant default
     * theme when content names an address nothing occupies. Published versions carry a frozen
     * `resolved_theme` snapshot and are unaffected.
     */
    THEME(
        CatalogResourceType.THEME,
        "themes",
        "id",
        setOf(ReferenceSiteKind.THEME_OVERRIDE),
        { runCatching { ThemeKey.of(it) }.exceptionOrNull()?.message },
    ),
    ;

    companion object {
        fun of(type: CatalogResourceType): MovableResource? = entries.firstOrNull { it.type == type }
    }
}
