export const SHORTCUT_CONTEXT_IDS = [
  'global',
  'editor',
  'insertDialog',
  'resizeHandle',
  'text',
] as const;

const SHORTCUT_CONTEXT_ID_SET = new Set<string>(SHORTCUT_CONTEXT_IDS);

const CORE_COMMAND_NAMESPACES = ['editor', 'text', 'insertDialog', 'resize'] as const;

const CORE_COMMAND_NAMESPACE_SET = new Set<string>(CORE_COMMAND_NAMESPACES);

const COMMAND_ID_SEGMENT_RE = /^[a-z][a-z0-9-]*$/;

export type ShortcutContextId = (typeof SHORTCUT_CONTEXT_IDS)[number];

export type CommandId =
  | `editor.${string}`
  | `text.${string}`
  | `insertDialog.${string}`
  | `resize.${string}`
  | `plugin.${string}.${string}`;

export const COMMAND_CATEGORIES = ['Leader', 'Core', 'Text', 'Insert', 'Resize', 'Plugin'] as const;

export type CommandCategory = (typeof COMMAND_CATEGORIES)[number];

export interface CommandResult {
  ok: boolean;
  message?: string;
  errorCode?: string;
}

export interface CommandExecutionContext {
  signal: AbortSignal;
}

export interface CommandDefinition<TContext = unknown> {
  id: CommandId;
  label: string;
  category: CommandCategory;
  run: (
    context: TContext,
    execution: CommandExecutionContext,
  ) => CommandResult | Promise<CommandResult>;
  metadata?: Record<string, unknown>;
}

export interface KeybindingDefinition<TContext = unknown> {
  commandId: CommandId;
  context: ShortcutContextId;
  keys: readonly string[];
  matchBy?: 'key' | 'code';
  preventDefault?: boolean;
  stopPropagation?: boolean;
  when?: (context: TContext) => boolean;
  display?: string;
}

export interface ShortcutRegistryDefinition<TContext = unknown> {
  commands: readonly CommandDefinition<TContext>[];
  keybindings: readonly KeybindingDefinition<TContext>[];
}

export type ShortcutRegistryValidationIssueCode =
  | 'invalid-command-id'
  | 'duplicate-command-id'
  | 'missing-command-reference'
  | 'empty-binding-keys'
  | 'invalid-binding-context'
  | 'invalid-binding-match-by'
  | 'binding-conflict';

export interface ShortcutRegistryValidationIssue {
  code: ShortcutRegistryValidationIssueCode;
  message: string;
}

export interface ShortcutRegistryValidationResult {
  valid: boolean;
  issues: ShortcutRegistryValidationIssue[];
}

interface SeenBindingKey {
  bindingIndex: number;
  commandId: string;
  hasUnconditional: boolean;
}

function isCommandIdSegment(value: string): boolean {
  return COMMAND_ID_SEGMENT_RE.test(value);
}

function normalizeBindingKey(value: string): string {
  return value.trim().replace(/\s+/g, ' ').toLowerCase();
}

function buildBindingConflictKey(context: ShortcutContextId, key: string): string {
  return `${context}::${key}`;
}

export function isShortcutContextId(value: string): value is ShortcutContextId {
  return SHORTCUT_CONTEXT_ID_SET.has(value);
}

export function isValidCommandId(value: string): value is CommandId {
  const segments = value.split('.');
  if (segments.length < 2 || segments.some((segment) => segment.length === 0)) {
    return false;
  }

  const [namespace, ...rest] = segments;
  if (!namespace) {
    return false;
  }

  if (namespace === 'plugin') {
    if (rest.length < 2) {
      return false;
    }
    return rest.every((segment) => isCommandIdSegment(segment));
  }

  if (!CORE_COMMAND_NAMESPACE_SET.has(namespace)) {
    return false;
  }

  return rest.every((segment) => isCommandIdSegment(segment));
}

export function defineShortcutRegistry<TContext>(
  registry: ShortcutRegistryDefinition<TContext>,
): ShortcutRegistryDefinition<TContext> {
  return registry;
}

/**
 * Merges multiple registries into a single registry with type-erased context.
 * Use when creating a unified resolver that handles commands from different
 * context types — context filtering via `activeContexts` ensures only the
 * right commands match at runtime.
 */
export function mergeRegistries(
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ...registries: readonly ShortcutRegistryDefinition<any>[]
): ShortcutRegistryDefinition<unknown> {
  return {
    commands: registries.flatMap((r) => r.commands),
    keybindings: registries.flatMap((r) => r.keybindings),
  };
}

export function validateShortcutRegistry<TContext>(
  registry: ShortcutRegistryDefinition<TContext>,
): ShortcutRegistryValidationResult {
  const issues: ShortcutRegistryValidationIssue[] = [];

  const seenCommandIds = new Map<string, number>();
  for (const [index, command] of registry.commands.entries()) {
    if (!isValidCommandId(command.id)) {
      issues.push({
        code: 'invalid-command-id',
        message: `Command at index ${index} has invalid id "${command.id}"`,
      });
    }

    const existingIndex = seenCommandIds.get(command.id);
    if (existingIndex !== undefined) {
      issues.push({
        code: 'duplicate-command-id',
        message: `Command id "${command.id}" is duplicated at indices ${existingIndex} and ${index}`,
      });
      continue;
    }

    seenCommandIds.set(command.id, index);
  }

  const seenBindings = new Map<string, SeenBindingKey>();
  for (const [index, binding] of registry.keybindings.entries()) {
    if (!isValidCommandId(binding.commandId)) {
      issues.push({
        code: 'invalid-command-id',
        message: `Binding at index ${index} has invalid command id "${binding.commandId}"`,
      });
    }

    if (!seenCommandIds.has(binding.commandId)) {
      issues.push({
        code: 'missing-command-reference',
        message: `Binding at index ${index} references missing command id "${binding.commandId}"`,
      });
    }

    if (!isShortcutContextId(binding.context)) {
      issues.push({
        code: 'invalid-binding-context',
        message: `Binding at index ${index} has invalid context "${binding.context}"`,
      });
    }

    if (binding.matchBy !== undefined && binding.matchBy !== 'key' && binding.matchBy !== 'code') {
      issues.push({
        code: 'invalid-binding-match-by',
        message: `Binding at index ${index} has invalid match mode "${String(binding.matchBy)}"`,
      });
    }

    const normalizedKeys = [
      ...new Set(
        binding.keys.map((key) => normalizeBindingKey(key)).filter((key) => key.length > 0),
      ),
    ];
    if (normalizedKeys.length === 0) {
      issues.push({
        code: 'empty-binding-keys',
        message: `Binding at index ${index} for command "${binding.commandId}" must define at least one key`,
      });
      continue;
    }

    const hasWhenPredicate = typeof binding.when === 'function';
    for (const normalizedKey of normalizedKeys) {
      const conflictKey = buildBindingConflictKey(binding.context, normalizedKey);
      const existing = seenBindings.get(conflictKey);
      if (!existing) {
        seenBindings.set(conflictKey, {
          bindingIndex: index,
          commandId: binding.commandId,
          hasUnconditional: !hasWhenPredicate,
        });
        continue;
      }

      const conflicts = existing.hasUnconditional || !hasWhenPredicate;
      if (conflicts) {
        issues.push({
          code: 'binding-conflict',
          message: `Binding conflict for key "${normalizedKey}" in context "${binding.context}" between commands "${existing.commandId}" (binding ${existing.bindingIndex}) and "${binding.commandId}" (binding ${index})`,
        });
      }

      if (!hasWhenPredicate) {
        existing.hasUnconditional = true;
      }
    }
  }

  return {
    valid: issues.length === 0,
    issues,
  };
}

export function formatShortcutRegistryIssues(
  issues: readonly ShortcutRegistryValidationIssue[],
): string {
  if (issues.length === 0) {
    return 'No validation issues';
  }
  return issues.map((issue, index) => `${index + 1}. [${issue.code}] ${issue.message}`).join('\n');
}

export function assertValidShortcutRegistry<TContext>(
  registry: ShortcutRegistryDefinition<TContext>,
): void {
  const result = validateShortcutRegistry(registry);
  if (!result.valid) {
    throw new Error(`Invalid shortcut registry:\n${formatShortcutRegistryIssues(result.issues)}`);
  }
}
