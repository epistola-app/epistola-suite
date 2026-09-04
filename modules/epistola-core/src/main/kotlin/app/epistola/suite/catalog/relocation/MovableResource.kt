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
     * generation history deliberately does not — see `V20260831161109__core_template_relocation`.
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
    ;

    companion object {
        fun of(type: CatalogResourceType): MovableResource? = entries.firstOrNull { it.type == type }
    }
}
