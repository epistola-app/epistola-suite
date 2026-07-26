// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Default style registry imported from the shared contract model.
 *
 * The font catalog mutates the fontFamily options at runtime, so export a
 * mutable clone instead of the package JSON object.
 */

import { styleRegistry } from '@epistola.app/epistola-catalog/registry';
import type { StyleRegistry } from '@epistola.app/epistola-catalog/generated/style-registry';

export const defaultStyleRegistry: StyleRegistry = structuredClone(styleRegistry);
