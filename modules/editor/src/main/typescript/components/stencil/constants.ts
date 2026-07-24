// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * String keys used by the stencil component throughout the document model.
 *
 * Centralised here so a typo in any single literal can't silently break the
 * stencil mechanism. Mirrored in:
 *   - Kotlin core: `modules/epistola-core/.../stencils/StencilNodeKeys.kt`
 *   - Kotlin renderer: `modules/generation/.../pdf/StencilNodeKeys.kt`
 *
 * The values are anchored by the JSON Schema in the `epistola-contract` repo;
 * any divergence between layers would surface in integration runs.
 */

/** Node `type` discriminator value for stencil nodes. */
export const STENCIL_TYPE = 'stencil';

/** Slot name for the stencil's children (the inlined published content). */
export const STENCIL_SLOT_CHILDREN = 'children';

/** Prop key — the stencil's stable identifier. Null when unlinked. */
export const STENCIL_PROP_STENCIL_ID = 'stencilId';

/** Prop key — catalog the stencil belongs to. Null when unlinked. */
export const STENCIL_PROP_CATALOG_KEY = 'catalogKey';

/** Prop key — the inlined published version number. */
export const STENCIL_PROP_VERSION = 'version';

/** Prop key — true when the user is editing the stencil definition in place. */
export const STENCIL_PROP_IS_DRAFT = 'isDraft';

/**
 * Prop key — JSON Schema snapshot of the stencil version's parameter schema,
 * copied onto the consuming stencil node at insert/upgrade time. Stencils
 * are dynamic components — each version has its own schema — so the schema
 * cannot live in a static component definition; the snapshot keeps it
 * accessible to the renderer / editor scope provider without a DB lookup.
 *
 * For the *generic* parameter-binding prop carried by every parametrised
 * component (not just stencils), see `engine/node-parameter-keys.ts`.
 */
export const STENCIL_PROP_PARAMETER_SCHEMA_SNAPSHOT = 'parameterSchemaSnapshot';
