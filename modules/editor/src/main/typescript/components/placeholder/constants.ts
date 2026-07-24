// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * String keys used by the placeholder component throughout the document model.
 *
 * Centralised here so a typo in any single literal can't silently break the
 * placeholder mechanism. Mirrored in:
 *   - Kotlin core: `modules/epistola-core/.../templates/validation/PlaceholderNodeKeys.kt`
 *   - Kotlin renderer: `modules/generation/.../pdf/StencilNodeKeys.kt`
 *
 * The values are anchored by the JSON Schema in the `epistola-contract` repo;
 * any divergence between layers would surface in integration runs.
 */

/** Node `type` discriminator value for placeholder nodes. */
export const PLACEHOLDER_TYPE = 'placeholder';

/** Slot name for the stencil author's default content. */
export const PLACEHOLDER_SLOT_DEFAULT = 'default';

/** Slot name for the embedding template's override; takes precedence when non-empty. */
export const PLACEHOLDER_SLOT_FILL = 'fill';

/** Prop key — placeholder identifier within a stencil (kebab-case slug). */
export const PLACEHOLDER_PROP_NAME = 'name';

/** Prop key — optional human-readable description. */
export const PLACEHOLDER_PROP_DESCRIPTION = 'description';

/** Prop key — kind of content allowed (only `'block'` in v1). */
export const PLACEHOLDER_PROP_KIND = 'kind';
