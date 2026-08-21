// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import type { SchemaField } from '../types.js';

export interface FieldLocation {
  field: SchemaField;
  parentFieldId: string | null;
  ancestors: SchemaField[];
  siblings: readonly SchemaField[];
  index: number;
}

export interface FieldTargetLabel {
  visible: string;
  accessible: string;
}

/** Locate a field and retain the hierarchy needed for contextual editor actions. */
export function findFieldLocation(
  fields: readonly SchemaField[],
  fieldId: string,
  ancestors: SchemaField[] = [],
): FieldLocation | null {
  for (const [index, field] of fields.entries()) {
    if (field.id === fieldId) {
      return {
        field,
        parentFieldId: ancestors.at(-1)?.id ?? null,
        ancestors,
        siblings: fields,
        index,
      };
    }

    const nested = nestedFields(field);
    if (nested.length > 0) {
      const found = findFieldLocation(nested, fieldId, [...ancestors, field]);
      if (found) return found;
    }
  }
  return null;
}

/** Return the parent-name path used when migrating renamed example keys. */
export function findFieldPath(
  fields: readonly SchemaField[],
  fieldId: string,
): { path: string[]; field: SchemaField } | null {
  const location = findFieldLocation(fields, fieldId);
  return location
    ? { path: location.ancestors.map((ancestor) => ancestor.name), field: location.field }
    : null;
}

/** Describe the object that receives a field without exposing schema syntax. */
export function fieldTargetLabel(
  fields: readonly SchemaField[],
  parentFieldId: string | null,
): FieldTargetLabel {
  if (parentFieldId === null) {
    return { visible: 'data contract', accessible: 'data contract' };
  }

  const location = findFieldLocation(fields, parentFieldId);
  if (!location) {
    return { visible: 'data contract', accessible: 'data contract' };
  }

  const segments = [...location.ancestors, location.field].map(fieldTargetSegment);
  return {
    visible: segments.join(' › '),
    accessible: segments.join(', '),
  };
}

/** IDs that must be expanded to reveal children added to the target object. */
export function fieldTargetAncestorIds(
  fields: readonly SchemaField[],
  parentFieldId: string,
): string[] {
  const location = findFieldLocation(fields, parentFieldId);
  return location ? [...location.ancestors, location.field].map((field) => field.id) : [];
}

/** Pick the first unused generated name within the receiving object. */
export function nextAvailableFieldName(
  fields: readonly SchemaField[],
  parentFieldId: string | null,
): string {
  const names = new Set(fieldsForParent(fields, parentFieldId).map((field) => field.name));
  let sequence = 1;
  while (names.has(`field${sequence}`)) sequence += 1;
  return `field${sequence}`;
}

/** Keep selection close to its former location when a structural edit removes it. */
export function reconcileFieldSelection(
  previousFields: readonly SchemaField[],
  nextFields: readonly SchemaField[],
  selectedFieldId: string | null,
): string | null {
  if (selectedFieldId === null) return nextFields[0]?.id ?? null;
  if (findFieldLocation(nextFields, selectedFieldId)) return selectedFieldId;

  const previous = findFieldLocation(previousFields, selectedFieldId);
  if (!previous) return nextFields[0]?.id ?? null;

  const nextSiblings = fieldsForParent(nextFields, previous.parentFieldId);
  const nextSibling = nextSiblings[previous.index];
  if (nextSibling) return nextSibling.id;

  const previousSibling = nextSiblings[previous.index - 1];
  if (previousSibling) return previousSibling.id;

  for (const ancestor of previous.ancestors.toReversed()) {
    if (findFieldLocation(nextFields, ancestor.id)) return ancestor.id;
  }

  return nextFields[0]?.id ?? null;
}

export function supportsNestedFields(field: SchemaField): boolean {
  return field.type === 'object' || (field.type === 'array' && field.arrayItemType === 'object');
}

function fieldsForParent(
  fields: readonly SchemaField[],
  parentFieldId: string | null,
): readonly SchemaField[] {
  if (parentFieldId === null) return fields;
  const parent = findFieldLocation(fields, parentFieldId)?.field;
  return parent ? nestedFields(parent) : [];
}

function nestedFields(field: SchemaField): readonly SchemaField[] {
  return field.type === 'object' || field.type === 'array' ? (field.nestedFields ?? []) : [];
}

function fieldTargetSegment(field: SchemaField): string {
  return field.type === 'array' && field.arrayItemType === 'object'
    ? `${field.name} items`
    : field.name;
}
