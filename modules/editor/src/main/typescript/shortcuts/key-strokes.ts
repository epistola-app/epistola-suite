/**
 * Expands a bare key into all modifier combinations so unmodified key shortcuts
 * also match when accidental modifiers are held (e.g. Shift+Delete still triggers
 * delete-selected-block). Returns unique stroke strings.
 */
export function withAnyModifiers(...keys: readonly string[]): string[] {
  const strokes = new Set<string>()

  for (const rawKey of keys) {
    const key = rawKey.toLowerCase()
    for (const mod of [false, true]) {
      for (const shift of [false, true]) {
        for (const alt of [false, true]) {
          const parts: string[] = []
          if (mod) parts.push('mod')
          if (shift) parts.push('shift')
          if (alt) parts.push('alt')
          parts.push(key)
          strokes.add(parts.join('+'))
        }
      }
    }
  }

  return [...strokes]
}
