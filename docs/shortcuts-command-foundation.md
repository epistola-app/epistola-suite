# Shortcuts Command Foundation

This document explains the core model behind the editor shortcut system.

The goal is simple: one shared model for command IDs, keybindings, and context scoping so runtime behavior and helper UI stay in sync.

## CommandId Namespace Rules

Use namespaced command IDs:
- `editor.*`
- `text.*`
- `insertDialog.*`
- `resize.*`
- `plugin.<name>.*`

Segment rules:
- Regex: `^[a-z][a-z0-9-]*$`
- Segments are dot-separated.
- Core namespaces require at least one segment after the namespace.
- Plugin namespaces require at least two segments after `plugin` (`plugin.<pluginName>.<commandPath>`).

Valid examples:
- `editor.preview.toggle`
- `text.mark.bold`
- `insertDialog.open`
- `resize.preview.grow`
- `plugin.ai.assist`

Invalid examples:
- `editor` (missing path)
- `plugin.ai` (plugin path too short)
- `unknown.preview.toggle` (unsupported namespace)
- `editor.Preview.toggle` (uppercase segment)
- `editor..toggle` (empty segment)

## ShortcutContextId Usage Rules

Available contexts:
- `global`: available across editor shell unless a stronger active context takes priority
- `editor`: editor shell interactions outside modal or focused text editing contexts
- `insertDialog`: only while insert dialog flow is active
- `resizeHandle`: only while resize handle interaction is active/focused
- `text`: only while rich text editor context is active

Practical rules:
- Bind each keybinding to the narrowest context that matches intent.
- Prefer non-`global` contexts for modal or focused flows.
- Keep context precedence deterministic in resolver order (active context first, then fallbacks).
- Use optional `when` only for conditional behavior inside the same context.

## Minimal Example (One Command + One Binding)

```ts
import {
  assertValidShortcutRegistry,
  defineShortcutRegistry,
  type CommandDefinition,
  type KeybindingDefinition,
} from '@/shortcuts/foundation.js'

interface EditorContext {
  togglePreview: () => void
}

const commands: readonly CommandDefinition<EditorContext>[] = [
  {
    id: 'editor.preview.toggle',
    label: 'Toggle preview',
    category: 'Leader',
    run: ({ togglePreview }, _execution) => {
      togglePreview()
      return { ok: true, message: 'Preview toggled' }
    },
  },
]

const keybindings: readonly KeybindingDefinition[] = [
  {
    commandId: 'editor.preview.toggle',
    context: 'global',
    keys: ['p'],
    display: 'Leader + P',
  },
]

const registry = defineShortcutRegistry({ commands, keybindings })
assertValidShortcutRegistry(registry)
```
