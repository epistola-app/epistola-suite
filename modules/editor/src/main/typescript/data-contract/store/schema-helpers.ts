import type { JsonSchema, JsonSchemaProperty, JsonValue } from '../types.js';
import { isRecord } from './logic.js';

export function getProperties(node: Record<string, unknown>): Record<string, unknown> | null {
  const properties = node.properties;
  return isRecord(properties) ? properties : null;
}

export function getItems(node: Record<string, unknown>): Record<string, unknown> | null {
  const items = node.items;
  return isRecord(items) ? items : null;
}

export function isJsonSchemaProperty(value: unknown): value is JsonSchemaProperty {
  if (!isRecord(value)) {
    return false;
  }

  const typeValue = value.type;
  if (typeof typeValue === 'string') {
    return true;
  }

  return Array.isArray(typeValue) && typeValue.every((item) => typeof item === 'string');
}

/**
 * Look up a property's JSON Schema by dot-separated path.
 */
export function getPropertySchema(schema: JsonSchema, dotPath: string): JsonSchemaProperty | null {
  const segments = dotPath.split('.');
  let current: unknown = schema;

  for (let i = 0; i < segments.length; i += 1) {
    if (!isRecord(current)) {
      return null;
    }

    const segment = segments[i];
    const props = getProperties(current);

    if (i === segments.length - 1) {
      let candidate: unknown = null;
      if (props) {
        candidate = props[segment];
      }
      if (isJsonSchemaProperty(candidate)) {
        return candidate;
      }

      // Numeric segment: return the array items schema
      if (/^\d+$/.test(segment)) {
        const items = getItems(current);
        if (isJsonSchemaProperty(items)) {
          return items;
        }
      }

      return null;
    }

    // Numeric segment: skip through array items schema
    if (/^\d+$/.test(segment)) {
      const items = getItems(current);
      if (!items) {
        return null;
      }

      current = items;
      continue;
    }

    let childFromProperties: unknown = null;
    if (props) {
      childFromProperties = props[segment];
    }
    if (isRecord(childFromProperties)) {
      current = childFromProperties;
      continue;
    }

    const items = getItems(current);
    if (!items) {
      return null;
    }

    const nestedProps = getProperties(items);
    let childFromItems: unknown = null;
    if (nestedProps) {
      childFromItems = nestedProps[segment];
    }
    if (isRecord(childFromItems)) {
      current = childFromItems;
    } else {
      return null;
    }
  }

  return isJsonSchemaProperty(current) ? current : null;
}

/**
 * Build a default value from a JSON Schema property definition.
 */
export function buildDefaultValue(propSchema: JsonSchemaProperty): JsonValue {
  const rawType = Array.isArray(propSchema.type) ? propSchema.type[0] : propSchema.type;
  const type = rawType === 'string' && propSchema.format === 'date' ? 'date' : rawType;

  switch (type) {
    case 'string':
    case 'date':
      return '';
    case 'number':
    case 'integer':
      return 0;
    case 'boolean':
      return false;
    case 'object': {
      if (!propSchema.properties) return {};
      const obj: Record<string, JsonValue> = {};
      for (const [key, child] of Object.entries(propSchema.properties)) {
        obj[key] = buildDefaultValue(child);
      }
      return obj;
    }
    case 'array': {
      if (propSchema.items) {
        return [buildDefaultValue(propSchema.items)];
      }
      return [];
    }
    default:
      return '';
  }
}
