/**
 * Store types — EditorState, FixScreenState, StoreCommand, Save types.
 *
 * Separate from types.ts to avoid circular dependencies with utils/.
 */

import type {
  CompatibilityMigrationSuggestion,
  DataExample,
  JsonObject,
  JsonSchema,
  JsonValue,
  SchemaEditMode,
  VisualSchema,
} from './types.js';
import type { SchemaCommand } from './utils/schemaCommands.js';
import type { SchemaCommandHistory } from './utils/schemaCommandHistory.js';
import type { SnapshotHistory } from './utils/snapshotHistory.js';
import type { SchemaValidationError } from './utils/schemaValidation.js';

// =============================================================================
// Fix screen types
// =============================================================================

/** Field value state in the fix screen */
export interface FixFieldValue {
  value: JsonValue | null;
  removed: boolean;
}

/** Fix screen state (null = closed) */
export interface FixScreenState {
  migrations: CompatibilityMigrationSuggestion[];
  newSchema: JsonSchema;
  fields: Map<string, FixFieldValue>;
  errors: Map<string, string>;
  /** Full edited copy of each example's data, keyed by exampleId */
  editedData: Map<string, JsonObject>;
}

// =============================================================================
// Save status
// =============================================================================

export type SaveStatus =
  | { type: 'idle' }
  | { type: 'saving' }
  | { type: 'success'; expiresAt: number }
  | { type: 'error'; message: string; canForceSave: boolean };

// =============================================================================
// Editor state
// =============================================================================

/** Editor state — single source of truth for the entire editor */
export interface EditorState {
  // Domain data
  schema: JsonSchema | null;
  committedSchema: JsonSchema | null;
  examples: DataExample[];
  committedExamples: DataExample[];

  // Schema editing
  visualSchema: VisualSchema;
  schemaEditMode: SchemaEditMode;
  rawJsonSchema: object | null;
  committedRawJsonSchema: object | null;
  schemaCommandHistory: SchemaCommandHistory;

  // UI
  activeTab: 'schema' | 'examples';
  selectedFieldId: string | null;
  selectedExampleId: string | null;
  expandedFields: Set<string>;
  schemaViewMode: 'visual' | 'json';

  // Fix screen (null = closed)
  fixScreen: FixScreenState | null;

  // Save status
  saveStatus: SaveStatus;

  // Validation
  validationErrors: Map<string, SchemaValidationError[]>;

  // Schema warnings
  schemaWarnings: Array<{ path: string; message: string }>;

  // Undo/redo
  exampleHistories: Map<string, SnapshotHistory<JsonObject>>;
}

// =============================================================================
// Store commands
// =============================================================================

export type StoreCommand =
  // Navigation
  | { type: 'select-tab'; tab: 'schema' | 'examples' }
  | { type: 'select-field'; fieldId: string | null }
  | { type: 'select-example'; exampleId: string | null }
  | { type: 'toggle-field-expand'; fieldId: string }
  | { type: 'set-schema-view-mode'; mode: 'visual' | 'json' }

  // Schema
  | { type: 'set-schema'; schema: JsonSchema | null }
  | {
      type: 'set-raw-json-schema';
      schema: object | null;
      mode: SchemaEditMode;
      asCommitted?: boolean;
    }
  | { type: 'execute-schema-command'; command: SchemaCommand }
  | { type: 'undo-schema' }
  | { type: 'redo-schema' }

  // Examples
  | { type: 'add-example'; example: DataExample }
  | { type: 'delete-example'; exampleId: string }
  | { type: 'update-example-name'; exampleId: string; name: string }
  | { type: 'update-example-data'; exampleId: string; path: string; value: JsonValue }
  | { type: 'clear-example-field'; exampleId: string; path: string }
  | { type: 'set-examples'; examples: DataExample[] }
  | { type: 'commit-examples' }
  | { type: 'undo-example' }
  | { type: 'redo-example' }

  // Fix screen
  | {
      type: 'open-fix-screen';
      migrations: CompatibilityMigrationSuggestion[];
      newSchema: JsonSchema;
    }
  | { type: 'fix-field-change'; exampleId: string; path: string; value: JsonValue }
  | { type: 'fix-remove-field'; exampleId: string; path: string }
  | { type: 'fix-remove-all-unknown' }
  | { type: 'close-fix-screen' }

  // Save
  | { type: 'set-saving' }
  | { type: 'set-schema-warnings'; warnings: Array<{ path: string; message: string }> }
  | { type: 'save-success' }
  | { type: 'save-error'; message: string; canForceSave?: boolean }
  | { type: 'clear-save-status' }
  | { type: 'revert-to-committed' };

// =============================================================================
// Save types
// =============================================================================

export type SaveIntent = { type: 'save' } | { type: 'force-save' } | { type: 'fix-and-save' };

export type SaveOutcome =
  | { action: 'save-schema'; force: boolean; examples?: DataExample[] }
  | { action: 'save-examples' }
  | {
      action: 'open-fix-screen';
      migrations: CompatibilityMigrationSuggestion[];
      newSchema: JsonSchema;
    }
  | { action: 'force-save'; message: string }
  | { action: 'error'; message: string; canForceSave: boolean }
  | { action: 'none' };
