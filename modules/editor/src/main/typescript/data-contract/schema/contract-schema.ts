// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

type SchemaNode = Record<string, unknown>;

export interface DataContractSchemaValidation {
  valid: boolean;
  error?: string;
}

/**
 * Validate the root-shape invariant for an Epistola data contract schema.
 *
 * JSON Schema allows unknown keywords, so an arbitrary JSON object is also a
 * vacuous schema. Epistola contracts intentionally use a narrower profile:
 * their root must constrain example data to an object. The backend performs
 * authoritative dialect/meta-schema validation when the draft is saved.
 */
export function validateDataContractSchema(schema: unknown): DataContractSchemaValidation {
  if (!isSchemaNode(schema)) {
    return { valid: false, error: 'JSON Schema must be a JSON object' };
  }

  if (!requiresObjectAtRoot(schema, schema, new Set())) {
    return {
      valid: false,
      error: 'A data contract JSON Schema must require an object at its root',
    };
  }

  return { valid: true };
}

function requiresObjectAtRoot(
  schema: SchemaNode,
  root: SchemaNode,
  resolvingReferences: ReadonlySet<string>,
): boolean {
  if (schema.type === 'object') return true;

  const reference = schema.$ref;
  if (
    typeof reference === 'string' &&
    reference.startsWith('#/') &&
    !resolvingReferences.has(reference)
  ) {
    const target = resolveLocalReference(root, reference);
    if (target) {
      const nextReferences = new Set(resolvingReferences);
      nextReferences.add(reference);
      if (requiresObjectAtRoot(target, root, nextReferences)) return true;
    }
  }

  if (
    Array.isArray(schema.allOf) &&
    schema.allOf.some(
      (member) => isSchemaNode(member) && requiresObjectAtRoot(member, root, resolvingReferences),
    )
  ) {
    return true;
  }

  return ['oneOf', 'anyOf'].some((keyword) => {
    const members = schema[keyword];
    return (
      Array.isArray(members) &&
      members.length > 0 &&
      members.every(
        (member) => isSchemaNode(member) && requiresObjectAtRoot(member, root, resolvingReferences),
      )
    );
  });
}

function resolveLocalReference(root: SchemaNode, reference: string): SchemaNode | null {
  let current: unknown = root;
  for (const encodedSegment of reference.slice(2).split('/')) {
    if (!isSchemaNode(current)) return null;
    const segment = encodedSegment.replace(/~1/g, '/').replace(/~0/g, '~');
    current = current[segment];
  }
  return isSchemaNode(current) ? current : null;
}

function isSchemaNode(value: unknown): value is SchemaNode {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
