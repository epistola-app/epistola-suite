import type { ShortcutBinding } from '../shortcuts-config.js'

export interface ShortcutStrokeOptions {
  wildcardUnspecifiedModifiers?: boolean
}

function expandModifierValues(
  value: boolean | undefined,
  wildcardUnspecifiedModifiers: boolean,
): readonly boolean[] {
  if (value === undefined) {
    return wildcardUnspecifiedModifiers ? [false, true] : [false]
  }
  return [value]
}

export function toShortcutStrokes(
  binding: ShortcutBinding,
  options: ShortcutStrokeOptions = {},
): string[] {
  const wildcardUnspecifiedModifiers = options.wildcardUnspecifiedModifiers ?? true
  const key = binding.key.toLowerCase()
  const strokes = new Set<string>()

  for (const mod of expandModifierValues(binding.mod, wildcardUnspecifiedModifiers)) {
    for (const shift of expandModifierValues(binding.shift, wildcardUnspecifiedModifiers)) {
      for (const alt of expandModifierValues(binding.alt, wildcardUnspecifiedModifiers)) {
        const parts: string[] = []
        if (mod) parts.push('mod')
        if (shift) parts.push('shift')
        if (alt) parts.push('alt')
        parts.push(key)
        strokes.add(parts.join('+'))
      }
    }
  }

  return [...strokes]
}

export function toShortcutStrokesFromBindings(
  bindings: readonly ShortcutBinding[],
  options: ShortcutStrokeOptions = {},
): string[] {
  return [...new Set(bindings.flatMap((binding) => toShortcutStrokes(binding, options)))]
}
