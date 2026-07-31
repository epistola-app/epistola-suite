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
import type { StyleRegistry } from '@epistola.app/epistola-catalog';

const COMPOUND_STYLE_LONGHANDS: Record<string, ReadonlySet<string>> = {
  margin: new Set(['marginTop', 'marginRight', 'marginBottom', 'marginLeft']),
  padding: new Set(['paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft']),
  border: new Set(['borderTop', 'borderRight', 'borderBottom', 'borderLeft']),
};

/**
 * Build the registry presented by editor UIs. The contract keeps canonical
 * longhand properties for validation and serialization, while the compound
 * controls edit those same keys and therefore replace the duplicate rows.
 */
export function createEditorStyleRegistry(source: StyleRegistry): StyleRegistry {
  const registry = structuredClone(source);
  for (const group of registry.groups) {
    const keys = new Set(group.properties.map((property) => property.key));
    const hiddenLonghands = new Set<string>();
    for (const [compound, longhands] of Object.entries(COMPOUND_STYLE_LONGHANDS)) {
      if (keys.has(compound)) {
        longhands.forEach((key) => hiddenLonghands.add(key));
      }
    }
    group.properties = group.properties.filter((property) => !hiddenLonghands.has(property.key));
  }
  return registry;
}

/** Whether a component allowlist makes an editor property available. */
export function isEditorStyleApplicable(propertyKey: string, applicableStyles: string[]): boolean {
  if (applicableStyles.includes(propertyKey)) return true;
  const longhands = COMPOUND_STYLE_LONGHANDS[propertyKey];
  return longhands ? applicableStyles.some((key) => longhands.has(key)) : false;
}

export const defaultStyleRegistry: StyleRegistry = createEditorStyleRegistry(styleRegistry);
