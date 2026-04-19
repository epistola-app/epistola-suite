/**
 * SaveOrchestrator — Pure save decision logic.
 *
 * Takes store state + save intent. Returns what action to take.
 * No DOM, no side effects. Testable with state-in, action-out.
 */

import type { DataExample, SchemaCompatibilityPreviewResult } from './types.js';
import type { DataContractStore } from './DataContractStore.js';
import type { SaveIntent, SaveOutcome } from './store-types.js';
import { getActiveSchema } from './utils/activeSchema.js';

/* oxlint-disable eslint/no-use-before-define */

/**
 * Pure save decision: read state, decide what to do.
 * This is called by the editor before executing the save.
 */
export function orchestrateSave(
  store: DataContractStore,
  intent: SaveIntent,
  compatibilityResult?: SchemaCompatibilityPreviewResult,
): SaveOutcome {
  const state = store.state;

  if (intent.type === 'force-save') {
    return { action: 'save-schema', force: true };
  }

  if (intent.type === 'fix-and-save') {
    // The fix screen already produced fixed examples. Save them atomically.
    const fixedExamples = store.buildFixedExamples();
    if (fixedExamples) {
      return { action: 'save-schema', force: false, examples: fixedExamples };
    }
    // If buildFixedExamples returns null, the fix screen is closed.
    return { action: 'none' };
  }

  // intent.type === 'save'
  const schemaDirty = store.isSchemaDirty;
  const examplesDirty = store.isExamplesDirty;

  if (!schemaDirty && !examplesDirty) {
    return { action: 'none' };
  }

  // Schema-only or examples-only saves
  if (!schemaDirty && examplesDirty) {
    return { action: 'save-examples' };
  }

  // Schema is dirty — check compatibility
  if (compatibilityResult && !compatibilityResult.compatible) {
    if (compatibilityResult.migrations.length > 0) {
      const schemaForMigration = getActiveSchema(state);

      if (schemaForMigration) {
        return {
          action: 'open-fix-screen',
          migrations: compatibilityResult.migrations,
          newSchema: schemaForMigration,
        };
      }
    }

    const hasBlockingExampleErrors = compatibilityResult.errors.length > 0;

    if (hasBlockingExampleErrors) {
      const message = buildCompatibilityErrorMessage(compatibilityResult);
      return { action: 'error', message, canForceSave: false };
    }
  }

  // Schema dirty, examples dirty → atomic save
  if (schemaDirty && examplesDirty) {
    return {
      action: 'save-schema',
      force: false,
      examples: state.examples,
    };
  }

  // Schema dirty only
  return { action: 'save-schema', force: false };
}

/**
 * Execute the save outcome by calling the appropriate store/orchestrator methods.
 * This performs the actual async work (side effects).
 */
// oxlint-disable-next-line oxc/no-async-await
export async function executeSave(
  store: DataContractStore,
  outcome: SaveOutcome,
): Promise<{ success: boolean; error?: string }> {
  const state = store.state;

  switch (outcome.action) {
    case 'none':
      return { success: true };

    case 'save-schema': {
      store.dispatch({ type: 'set-saving' });

      const schemaForPruning = getActiveSchema(state);

      // Strict mode: always persist schema-aligned examples when saving schema.
      // This removes unknown keys that are no longer present in schema.
      const prunedExamples = store.pruneExamplesForSchema(schemaForPruning);
      const examplesToSave = outcome.examples
        ? mergeFixedExamples(prunedExamples, outcome.examples)
        : prunedExamples;

      const schemaResult = await store.saveSchema(outcome.force, examplesToSave);
      if (!schemaResult.success) {
        const canForceSave = deriveCanForceSaveOnSchemaError();
        store.dispatch({
          type: 'save-error',
          message: schemaResult.error ?? 'Failed to save schema',
          canForceSave,
        });
        store.dispatch({
          type: 'set-schema-warnings',
          warnings: schemaResult.warnings ? Object.values(schemaResult.warnings).flat() : [],
        });
        return { success: false, error: schemaResult.error };
      }

      store.markSchemaCommandHistoryClear();

      // Atomic save: update store examples with fixed values BEFORE validation
      store.dispatch({ type: 'set-examples', examples: examplesToSave });

      store.validateAllExamples();
      store.dispatch({
        type: 'set-schema-warnings',
        warnings: schemaResult.warnings ? Object.values(schemaResult.warnings).flat() : [],
      });

      // Mark examples as committed (dirty flag reset)
      store.dispatch({ type: 'commit-examples' });
      store.clearExampleHistories();

      store.dispatch({ type: 'save-success' });
      return { success: true };
    }

    case 'save-examples': {
      store.dispatch({ type: 'set-saving' });

      const examplesResult = await store.saveExamples();
      if (!examplesResult.success) {
        store.dispatch({
          type: 'save-error',
          message: examplesResult.error ?? 'Failed to save examples',
          canForceSave: false,
        });
        return { success: false, error: examplesResult.error };
      }

      store.clearExampleHistories();
      store.dispatch({ type: 'save-success' });
      return { success: true };
    }

    case 'error':
      store.dispatch({
        type: 'save-error',
        message: outcome.message,
        canForceSave: outcome.canForceSave,
      });
      return { success: false, error: outcome.message };

    case 'open-fix-screen':
      store.dispatch({
        type: 'open-fix-screen',
        migrations: outcome.migrations,
        newSchema: outcome.newSchema,
      });
      return { success: true };

    case 'force-save':
      store.dispatch({
        type: 'save-error',
        message: outcome.message,
        canForceSave: true,
      });
      return { success: false, error: outcome.message };
    default: {
      const _exhaustive: never = outcome;
      return _exhaustive;
    }
  }
}

function mergeFixedExamples(
  allExamples: DataExample[],
  fixedExamples: DataExample[],
): DataExample[] {
  const fixedById = new Map(fixedExamples.map((example) => [example.id, example]));

  return allExamples.map((example) => fixedById.get(example.id) ?? example);
}

/**
 * Build a user-facing error message from a compatibility result.
 */
function buildCompatibilityErrorMessage(result: SchemaCompatibilityPreviewResult): string {
  if (result.errors.length > 0) {
    return 'Schema incompatible with current test data.';
  }

  return 'Schema compatibility check failed.';
}

/**
 * Flatten a compatibility result into schema warnings for display.
 */
export function flattenCompatibilityWarnings(
  result: SchemaCompatibilityPreviewResult,
): Array<{ path: string; message: string }> {
  const warnings: Array<{ path: string; message: string }> = [];

  for (const error of result.errors) {
    warnings.push({ path: error.path, message: error.message });
  }

  for (const migration of result.migrations) {
    warnings.push({
      path: migration.path,
      message: `${migration.issue}: expected ${migration.expectedType}`,
    });
  }

  for (const issue of result.recentUsage.issues) {
    for (const error of issue.errors) {
      const correlation = issue.correlationKey ? ` correlation=${issue.correlationKey}` : '';
      warnings.push({
        path: `request:${issue.requestId} ${error.path}`.trim(),
        message: `${error.message} [status=${issue.status}${correlation}]`,
      });
    }
  }

  if (!result.recentUsage.available) {
    warnings.push({
      path: 'recentUsage',
      message:
        result.recentUsage.unavailableReason ??
        'Recent usage compatibility check is temporarily unavailable.',
    });
  }

  return warnings;
}

function deriveCanForceSaveOnSchemaError(): boolean {
  return false;
}
