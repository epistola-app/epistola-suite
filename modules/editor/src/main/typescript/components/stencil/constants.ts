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
 * The values are anchored by the JSON Schema in the `epistola-contract` repo
 * (wire v8, ADR 0006: stencil instances by reference).
 */

/** Node `type` discriminator value for stencil nodes. */
export const STENCIL_TYPE = 'stencil';

/**
 * Slot name for the stencil's children. Stored, it holds one placeholder per
 * fill; expanded (as the editor edits it), the stencil's content with the
 * fills in their placeholders.
 */
export const STENCIL_SLOT_CHILDREN = 'children';

/** Prop key — the key of the stencil the instance uses. Null when unlinked. */
export const STENCIL_PROP_STENCIL_KEY = 'stencilKey';

/**
 * Prop key — catalog of the stencil. Absent means the catalog of the
 * document the instance is written in.
 */
export const STENCIL_PROP_CATALOG_KEY = 'catalogKey';

/**
 * Prop key — the stencil's parameter schema, set on an instance while it is
 * expanded (never stored). Stencils are dynamic components, so the schema
 * cannot live in a static component definition.
 *
 * For the *generic* parameter-binding prop carried by every parametrised
 * component (not just stencils), see `engine/node-parameter-keys.ts`.
 */
export const STENCIL_PROP_PARAMETER_SCHEMA_SNAPSHOT = 'parameterSchemaSnapshot';

/**
 * Prop key — set to `true` on an expanded instance whose stencil reads the
 * using template's data (ADR 0006 part 5). Never stored.
 */
export const STENCIL_PROP_READS_TEMPLATE_DATA = 'readsTemplateData';

/** Props an instance carries only while expanded; the stored form drops them. */
export const STENCIL_TRANSIENT_PROPS: readonly string[] = [
  STENCIL_PROP_PARAMETER_SCHEMA_SNAPSHOT,
  STENCIL_PROP_READS_TEMPLATE_DATA,
];
