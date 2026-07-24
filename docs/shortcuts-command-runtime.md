# Shortcuts Command Runtime

This document defines runtime behavior for keyboard event normalization, resolution, chords, event policy, and command execution.

## Key Matching (`key` vs `code`)

Bindings support two match modes:

- `matchBy: 'key'` (default): layout-aware matching using normalized `KeyboardEvent.key`.
- `matchBy: 'code'`: layout-independent matching using normalized `KeyboardEvent.code`.

Per-stroke override:

- A stroke can opt into code matching with `code:` prefix.
- Example: `mod+code:space p`
  - first stroke uses `code` (`Space`),
  - second stroke uses default binding mode (`key` unless overridden).

Normalization rules:

- Modifiers are canonicalized to `mod`, `shift`, `alt`.
- `mod` resolves cross-platform (`Ctrl` on Windows/Linux, `Cmd` on macOS).
- Aliases like `esc`, arrow aliases, and space are normalized.
- Canonical stroke format is `<modifiers>+<token>` (for example `mod+shift+s`).

## Resolution Order

Resolution order is deterministic:

1. Active contexts in provided order.
2. Fallback contexts (default includes `global`).
3. Bindings in registration order within each context.

Additional precedence behavior:

- In the same context, direct single-stroke command matches are preferred over chord starts.
- Optional `when` predicates are evaluated before match acceptance.

## Chord Model

Chord bindings use multi-stroke sequences in one key string:

- Example: `mod+space p`

Runtime behavior:

- First stroke starts chord state.
- Follow-up strokes advance candidates until completion.
- Chord state is scoped to the context where it started.

Timeout and cancellation:

- Chord timeout is configurable (`timeoutMs`).
- Cancel keys are configurable (default includes `escape`).
- Cancellation reasons:
  - `timeout`
  - `cancel-key`
  - `mismatch`
  - `context-changed`
  - `manual`

## Event Policy

Bindings can declare:

- `preventDefault?: boolean`
- `stopPropagation?: boolean`

Event policy is applied from:

- resolved command binding, or
- active chord candidate set while awaiting follow-up.

## Command Execution Contract

Commands support both sync and async handlers.

Handler signature:

- `run(context, { signal })`

Execution statuses:

- `pending` (async started)
- `ok`
- `rejected`
- `error`
- `cancelled`

Cancellation behavior:

- Execution exposes `cancel(reason?)`.
- Underlying signal is aborted via `AbortController`.
- If cancelled before completion, final status resolves to `cancelled`.

## Validation

Shortcut registries are validated on startup before runtime dispatch.

Validation and regression coverage run as part of the standard editor test/build pipeline.
