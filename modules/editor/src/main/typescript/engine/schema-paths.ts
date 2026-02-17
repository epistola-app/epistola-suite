/**
 * JSON Schema → field path extractor.
 *
 * Walks a JSON Schema's `properties` recursively and returns
 * dot-notation paths suitable for expression autocomplete.
 */

export interface FieldPath {
  /** Dot-notation path, e.g. "customer.address.city" */
  path: string
  /** JSON Schema type at this path */
  type: string
}

const MAX_DEPTH = 5

/**
 * Extract field paths from a JSON Schema object.
 *
 * Walks `properties` recursively (up to MAX_DEPTH levels).
 * For arrays with `items`, appends `[]` and continues into
 * the items schema if it has properties.
 */
export function extractFieldPaths(schema: object): FieldPath[] {
  const result: FieldPath[] = []
  walk(schema as Record<string, unknown>, '', 0, result)
  return result
}

function walk(
  schema: Record<string, unknown>,
  prefix: string,
  depth: number,
  result: FieldPath[],
): void {
  if (depth > MAX_DEPTH) return

  const properties = schema.properties as Record<string, Record<string, unknown>> | undefined
  if (!properties || typeof properties !== 'object') return

  for (const [key, propSchema] of Object.entries(properties)) {
    if (!propSchema || typeof propSchema !== 'object') continue

    const path = prefix ? `${prefix}.${key}` : key
    const type = String(propSchema.type ?? 'unknown')

    result.push({ path, type })

    if (type === 'object') {
      walk(propSchema, path, depth + 1, result)
    } else if (type === 'array') {
      const items = propSchema.items as Record<string, unknown> | undefined
      if (items && typeof items === 'object') {
        const itemType = String(items.type ?? 'unknown')
        const arrayPath = `${path}[]`
        if (itemType === 'object') {
          walk(items, arrayPath, depth + 1, result)
        }
      }
    }
  }
}
