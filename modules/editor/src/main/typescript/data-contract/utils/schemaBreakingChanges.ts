/**
 * Breaking change detection — compares committed vs draft visual schema
 * to surface changes that affect external systems consuming the data contract.
 *
 * Uses stable field IDs to distinguish renames from add/delete pairs.
 */

import type { SchemaField, SchemaFieldType } from '../types.js';

export type BreakingChangeType = 'removed' | 'renamed' | 'type_changed';

export interface BreakingChange {
  type: BreakingChangeType;
  path: string;
  description: string;
}

/**
 * Detect breaking changes between two visual schema field trees.
 * Returns an empty array if there are no breaking changes.
 */
export function detectBreakingChanges(
  oldFields: readonly SchemaField[],
  newFields: readonly SchemaField[],
  basePath = '',
): BreakingChange[] {
  const changes: BreakingChange[] = [];
  const newById = indexById(newFields);

  for (const oldField of oldFields) {
    const path = basePath ? `${basePath}.${oldField.name}` : oldField.name;
    const newField = newById.get(oldField.id);

    if (!newField) {
      // Field removed (ID no longer exists)
      changes.push({
        type: 'removed',
        path,
        description: `"${oldField.name}" removed`,
      });
      continue;
    }

    if (newField.name !== oldField.name) {
      changes.push({
        type: 'renamed',
        path,
        description: `"${oldField.name}" renamed to "${newField.name}"`,
      });
    }

    if (effectiveType(newField) !== effectiveType(oldField)) {
      const displayPath =
        newField.name !== oldField.name
          ? `${basePath ? basePath + '.' : ''}${newField.name}`
          : path;
      changes.push({
        type: 'type_changed',
        path: displayPath,
        description: `"${newField.name}" type changed from ${effectiveType(oldField)} to ${effectiveType(newField)}`,
      });
    }

    // Recurse into nested fields
    const oldNested = getNestedFields(oldField);
    const newNested = getNestedFields(newField);
    if (oldNested && newNested) {
      const nestedPath =
        newField.name !== oldField.name
          ? `${basePath ? basePath + '.' : ''}${newField.name}`
          : path;
      changes.push(...detectBreakingChanges(oldNested, newNested, nestedPath));
    } else if (oldNested && !newNested) {
      // Nested fields removed (e.g. object → string)
      for (const nested of oldNested) {
        changes.push({
          type: 'removed',
          path: `${path}.${nested.name}`,
          description: `"${nested.name}" removed (parent type changed)`,
        });
      }
    }
  }

  return changes;
}

function indexById(fields: readonly SchemaField[]): Map<string, SchemaField> {
  const map = new Map<string, SchemaField>();
  for (const f of fields) map.set(f.id, f);
  return map;
}

function effectiveType(field: SchemaField): string {
  if (field.type === 'array' && 'arrayItemType' in field) {
    return `array<${(field as { arrayItemType: SchemaFieldType }).arrayItemType}>`;
  }
  return field.type;
}

function getNestedFields(field: SchemaField): readonly SchemaField[] | undefined {
  if ((field.type === 'object' || field.type === 'array') && 'nestedFields' in field) {
    return (field as { nestedFields?: SchemaField[] }).nestedFields;
  }
  return undefined;
}
