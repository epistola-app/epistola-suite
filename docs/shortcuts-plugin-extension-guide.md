# Shortcuts Plugin Extension Guide

Use this guide when adding plugin-provided keyboard shortcuts.

## Goal

Plugin shortcuts must be safe to merge with core shortcuts, deterministic at runtime, and validated at startup.

## Rules

- Use the `plugin.<pluginId>.*` command namespace.
- Keep `pluginId` lowercase slug format (`^[a-z][a-z0-9-]*$`).
- Register plugin shortcuts through typed contributions, not ad-hoc maps.
- Validate plugin contributions before runtime.
- Merge plugin and core registries through the merge helpers to detect drift/conflicts.

## Recommended Flow

### 1) Define plugin shortcut contribution

```ts
import {
  definePluginShortcutContribution,
  type PluginShortcutContribution,
} from '@/shortcuts/plugin-registry.js'

interface AiShortcutContext {
  openPanel: () => void
}

const aiShortcuts: PluginShortcutContribution<AiShortcutContext> =
  definePluginShortcutContribution({
    pluginId: 'ai',
    commands: [
      {
        id: 'plugin.ai.open-panel',
        label: 'Open AI panel',
        category: 'Plugin',
        run: ({ openPanel }) => {
          openPanel()
          return { ok: true }
        },
      },
    ],
    keybindings: [
      {
        commandId: 'plugin.ai.open-panel',
        context: 'global',
        keys: ['mod+space g'],
        when: () => true,
        display: 'Leader + G',
      },
    ],
  })
```

### 2) Validate at startup

```ts
import { validateShortcutRegistriesOnStartup } from '@/shortcuts/startup-validation.js'

validateShortcutRegistriesOnStartup([aiShortcuts])
```

## What Validation Checks

Validation covers:
- plugin id shape
- plugin namespace enforcement
- missing command references
- invalid contexts or key definitions
- duplicate command IDs
- keybinding conflicts against core registries

## Expected Failure Modes

Validation is intentionally strict and fails startup for:
- command IDs outside `plugin.<pluginId>.*`
- binding references to missing commands
- plugin/core duplicate command IDs
- plugin/core key conflicts in the same context

## Operational Guidance

- Treat startup validation failures as release blockers.
- Keep plugin shortcuts behind versioned migration notes when keys change.
- Add regression tests for plugin context interactions before enabling in production.
