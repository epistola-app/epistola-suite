/**
 * Deep-freeze an object to prevent accidental mutation.
 *
 * In production builds, Vite's dead-code elimination removes the freeze
 * calls entirely when `import.meta.env.PROD` is true.
 */
export function deepFreeze<T>(obj: T): T {
  if (import.meta.env.PROD) {
    return obj
  }

  if (obj === null || typeof obj !== 'object') return obj

  Object.freeze(obj)

  for (const value of Object.values(obj as Record<string, unknown>)) {
    if (value !== null && typeof value === 'object' && !Object.isFrozen(value)) {
      deepFreeze(value)
    }
  }

  return obj
}
