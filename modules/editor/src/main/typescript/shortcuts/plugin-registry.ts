import {
  assertValidShortcutRegistry,
  defineShortcutRegistry,
  validateShortcutRegistry,
  type CommandDefinition,
  type KeybindingDefinition,
  type ShortcutRegistryDefinition,
} from "./foundation.js";

const PLUGIN_ID_PATTERN = /^[a-z][a-z0-9-]*$/;

export interface PluginShortcutContribution<TContext = unknown> {
  pluginId: string;
  commands: readonly CommandDefinition<TContext>[];
  keybindings: readonly KeybindingDefinition[];
}

export type PluginShortcutValidationIssueCode =
  | "invalid-plugin-id"
  | "invalid-plugin-command-id"
  | "invalid-plugin-binding-command-id"
  | "invalid-command-id"
  | "duplicate-command-id"
  | "missing-command-reference"
  | "empty-binding-keys"
  | "invalid-binding-context"
  | "invalid-binding-match-by"
  | "binding-conflict";

export interface PluginShortcutValidationIssue {
  code: PluginShortcutValidationIssueCode;
  message: string;
}

export interface PluginShortcutValidationResult {
  valid: boolean;
  issues: PluginShortcutValidationIssue[];
}

export function definePluginShortcutContribution<TContext>(
  contribution: PluginShortcutContribution<TContext>,
): PluginShortcutContribution<TContext> {
  return contribution;
}

function pluginCommandPrefix(pluginId: string): string {
  return `plugin.${pluginId}.`;
}

function toContributionRegistry<TContext>(
  contribution: PluginShortcutContribution<TContext>,
): ShortcutRegistryDefinition<TContext> {
  return defineShortcutRegistry({
    commands: contribution.commands,
    keybindings: contribution.keybindings,
  });
}

export function validatePluginShortcutContribution<TContext>(
  contribution: PluginShortcutContribution<TContext>,
): PluginShortcutValidationResult {
  const issues: PluginShortcutValidationIssue[] = [];

  if (!PLUGIN_ID_PATTERN.test(contribution.pluginId)) {
    issues.push({
      code: "invalid-plugin-id",
      message: `Invalid plugin id "${contribution.pluginId}". Use lowercase slug segments only.`,
    });
  }

  const expectedPrefix = pluginCommandPrefix(contribution.pluginId);

  for (const [index, command] of contribution.commands.entries()) {
    if (!command.id.startsWith(expectedPrefix)) {
      issues.push({
        code: "invalid-plugin-command-id",
        message: `Plugin command at index ${index} must use prefix "${expectedPrefix}" but received "${command.id}"`,
      });
    }
  }

  for (const [index, binding] of contribution.keybindings.entries()) {
    if (!binding.commandId.startsWith(expectedPrefix)) {
      issues.push({
        code: "invalid-plugin-binding-command-id",
        message: `Plugin binding at index ${index} must reference command ids under "${expectedPrefix}" but received "${binding.commandId}"`,
      });
    }
  }

  const foundationValidation = validateShortcutRegistry(toContributionRegistry(contribution));
  for (const issue of foundationValidation.issues) {
    issues.push(issue);
  }

  return {
    valid: issues.length === 0,
    issues,
  };
}

export function formatPluginShortcutIssues(
  issues: readonly PluginShortcutValidationIssue[],
): string {
  if (issues.length === 0) {
    return "No plugin shortcut validation issues";
  }

  return issues.map((issue, index) => `${index + 1}. [${issue.code}] ${issue.message}`).join("\n");
}

export function assertValidPluginShortcutContribution<TContext>(
  contribution: PluginShortcutContribution<TContext>,
): void {
  const validation = validatePluginShortcutContribution(contribution);
  if (!validation.valid) {
    throw new Error(
      `Invalid plugin shortcut contribution:\n${formatPluginShortcutIssues(validation.issues)}`,
    );
  }
}

export function toPluginShortcutRegistry<TContext>(
  contribution: PluginShortcutContribution<TContext>,
): ShortcutRegistryDefinition<TContext> {
  assertValidPluginShortcutContribution(contribution);
  return toContributionRegistry(contribution);
}

export function mergeShortcutRegistries<TContext>(
  registries: readonly ShortcutRegistryDefinition<TContext>[],
): ShortcutRegistryDefinition<TContext> {
  return defineShortcutRegistry({
    commands: registries.flatMap((registry) => registry.commands),
    keybindings: registries.flatMap((registry) => registry.keybindings),
  });
}

export function assertValidMergedShortcutRegistries<TContext>(
  registries: readonly ShortcutRegistryDefinition<TContext>[],
): ShortcutRegistryDefinition<TContext> {
  const merged = mergeShortcutRegistries(registries);
  assertValidShortcutRegistry(merged);
  return merged;
}
