import { getEditorShortcutRegistry } from './editor-runtime.js'
import { getInsertDialogShortcutRegistry } from './insert-dialog-runtime.js'
import {
  assertValidMergedShortcutRegistries,
  toPluginShortcutRegistry,
  type PluginShortcutContribution,
} from './plugin-registry.js'
import { getResizeShortcutRegistry } from './resize-runtime.js'
import { getTextShortcutRegistry } from './text-runtime.js'
import type { ShortcutRegistryDefinition } from './foundation.js'

let coreValidationCompleted = false

type AnyShortcutRegistry = ShortcutRegistryDefinition<any>

export function getCoreShortcutRegistries(): readonly AnyShortcutRegistry[] {
  return [
    getEditorShortcutRegistry() as AnyShortcutRegistry,
    getInsertDialogShortcutRegistry() as AnyShortcutRegistry,
    getResizeShortcutRegistry() as AnyShortcutRegistry,
    getTextShortcutRegistry() as AnyShortcutRegistry,
  ]
}

export function validateShortcutRegistriesOnStartup(
  pluginContributions: readonly PluginShortcutContribution<unknown>[] = [],
): void {
  const pluginRegistries = pluginContributions.map((contribution) => {
    return toPluginShortcutRegistry(contribution) as AnyShortcutRegistry
  })
  assertValidMergedShortcutRegistries([
    ...getCoreShortcutRegistries(),
    ...pluginRegistries,
  ])
}

export function validateCoreShortcutRegistriesOnStartup(): void {
  if (coreValidationCompleted) {
    return
  }

  validateShortcutRegistriesOnStartup()
  coreValidationCompleted = true
}
