import { useState, useCallback, useMemo, useEffect, useRef } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { javascript } from "@codemirror/lang-javascript";
import { Settings2, Plus, Trash2, Wand2, AlertTriangle, Check } from "lucide-react";
import { v4 as uuidv4 } from "uuid";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { SchemaEditor } from "@/components/schema/SchemaEditor";
import { useEditorStore } from "../../store/editorStore";
import {
  useDataContractDraft,
  type SaveCallbacks,
  type SchemaCompatibilityResult,
} from "../../hooks/useDataContractDraft";
import {
  ValidationMessages,
  flattenErrorsByExample,
  type ValidationError,
} from "./ValidationMessages";
import { MigrationAssistant } from "./MigrationAssistant";
import { DialogFooterActions, ConfirmClosePrompt } from "./DialogFooterActions";
import type { DataExample, JsonObject } from "../../types/template";
import { JsonObjectSchema } from "../../types/template";
import type { VisualSchema, JsonSchema } from "../../types/schema";
import {
  jsonSchemaToVisualSchema,
  visualSchemaToJsonSchema,
  generateSchemaFromData,
} from "../../utils/schemaUtils";
import { validateDataAgainstSchema } from "../../utils/schemaValidation";
import {
  detectMigrations,
  applyAllMigrations,
  type MigrationSuggestion,
} from "../../utils/schemaMigration";

const DEFAULT_EXAMPLE_VALUE = "__default__" as const;

/**
 * Result of updating a single data example
 */
interface UpdateDataExampleResult {
  success: boolean;
  example?: DataExample;
  warnings?: Record<string, ValidationError[]>;
  errors?: Record<string, ValidationError[]>;
}

interface DataContractManagerProps {
  onSaveDataExamples?: (
    examples: DataExample[],
  ) => Promise<{ success: boolean; warnings?: Record<string, ValidationError[]> }>;
  onUpdateDataExample?: (
    exampleId: string,
    updates: { name?: string; data?: JsonObject },
    forceUpdate?: boolean,
  ) => Promise<UpdateDataExampleResult>;
  onDeleteDataExample?: (exampleId: string) => Promise<{ success: boolean }>;
  onSaveSchema?: (
    schema: JsonSchema | null,
    forceUpdate?: boolean,
  ) => Promise<{ success: boolean; warnings?: Record<string, ValidationError[]> }>;
  onValidateSchema?: (
    schema: JsonSchema,
    examples?: DataExample[],
  ) => Promise<SchemaCompatibilityResult>;
}

export function DataContractManager({
  onSaveDataExamples,
  onUpdateDataExample,
  onDeleteDataExample,
  onSaveSchema,
  onValidateSchema,
}: DataContractManagerProps) {
  const dataExamples = useEditorStore((s) => s.dataExamples);
  const schema = useEditorStore((s) => s.schema);
  const selectedDataExampleId = useEditorStore((s) => s.selectedDataExampleId);
  const selectDataExample = useEditorStore((s) => s.selectDataExample);

  const [dialogOpen, setDialogOpen] = useState(false);

  // Track which examples are invalid against the current schema
  const invalidExampleIds = useMemo(() => {
    if (!schema) return new Set<string>();
    const invalid = new Set<string>();
    for (const example of dataExamples) {
      const result = validateDataAgainstSchema(example.data as JsonObject, schema);
      if (!result.valid) {
        invalid.add(example.id);
      }
    }
    return invalid;
  }, [schema, dataExamples]);

  const handleSelectChange = useCallback(
    (value: string) => {
      if (value === DEFAULT_EXAMPLE_VALUE) {
        selectDataExample(null);
      } else {
        selectDataExample(value);
      }
    },
    [selectDataExample],
  );

  const selectedValue = selectedDataExampleId ?? DEFAULT_EXAMPLE_VALUE;

  // Create callbacks for the draft hook
  const callbacks: SaveCallbacks = useMemo(
    () => ({
      onSaveSchema,
      onSaveDataExamples,
      onUpdateDataExample,
      onDeleteDataExample,
      onValidateSchema,
    }),
    [onSaveSchema, onSaveDataExamples, onUpdateDataExample, onDeleteDataExample, onValidateSchema],
  );

  return (
    <div className="flex items-center gap-2">
      <span className="text-xs font-medium text-slate-600">Test Data:</span>
      <Select value={selectedValue} onValueChange={handleSelectChange}>
        <SelectTrigger
          size="sm"
          className="text-xs px-2 py-1 border border-slate-200 rounded-md bg-white hover:border-slate-300 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 w-44 h-7"
        >
          <SelectValue placeholder="Default" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={DEFAULT_EXAMPLE_VALUE}>Default (Built-in)</SelectItem>
          {dataExamples.map((example) => {
            const isInvalid = invalidExampleIds.has(example.id);
            return (
              <SelectItem
                key={example.id}
                value={example.id}
                disabled={isInvalid}
                className={isInvalid ? "text-muted-foreground" : ""}
              >
                <span className="flex items-center gap-1">
                  {example.name}
                  {isInvalid && <AlertTriangle className="h-3 w-3 text-amber-500" />}
                </span>
              </SelectItem>
            );
          })}
        </SelectContent>
      </Select>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogTrigger asChild>
          <Button variant="ghost" size="icon" className="h-7 w-7">
            <Settings2 className="h-4 w-4" />
          </Button>
        </DialogTrigger>
        <DialogContent className="sm:max-w-3xl max-h-[85vh] overflow-hidden flex flex-col">
          <DataContractDialog callbacks={callbacks} onClose={() => setDialogOpen(false)} />
        </DialogContent>
      </Dialog>
    </div>
  );
}

type TabId = "schema" | "test-data";

interface DataContractDialogProps {
  callbacks: SaveCallbacks;
  onClose: () => void;
}

function DataContractDialog({ callbacks, onClose }: DataContractDialogProps) {
  const [activeTab, setActiveTab] = useState<TabId>("schema");
  const draft = useDataContractDraft(callbacks);
  const [showConfirmClose, setShowConfirmClose] = useState(false);

  const handleClose = useCallback(() => {
    if (draft.isDirty) {
      setShowConfirmClose(true);
    } else {
      onClose();
    }
  }, [draft.isDirty, onClose]);

  const handleConfirmClose = useCallback(() => {
    draft.discardDraft();
    setShowConfirmClose(false);
    onClose();
  }, [draft, onClose]);

  return (
    <>
      <DialogHeader>
        <DialogTitle>Data Contract</DialogTitle>
        <DialogDescription>
          Define the schema for your template data and manage test examples.
        </DialogDescription>
      </DialogHeader>

      {/* Tab navigation */}
      <div className="flex border-b">
        <button
          type="button"
          onClick={() => setActiveTab("schema")}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
            activeTab === "schema"
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          }`}
        >
          Schema
          {draft.isSchemaDirty && <span className="ml-1 text-amber-500">*</span>}
        </button>
        <button
          type="button"
          onClick={() => setActiveTab("test-data")}
          className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
            activeTab === "test-data"
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          }`}
        >
          Test Data
          {draft.isExamplesDirty && <span className="ml-1 text-amber-500">*</span>}
        </button>
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-auto min-h-0">
        {activeTab === "schema" ? (
          <SchemaTab draft={draft} onClose={handleClose} />
        ) : (
          <TestDataTab draft={draft} onClose={handleClose} />
        )}
      </div>

      {/* Confirm close dialog */}
      <ConfirmClosePrompt
        open={showConfirmClose}
        onConfirm={handleConfirmClose}
        onCancel={() => setShowConfirmClose(false)}
      />
    </>
  );
}

interface SchemaTabProps {
  draft: ReturnType<typeof useDataContractDraft>;
  onClose: () => void;
}

function SchemaTab({ draft, onClose }: SchemaTabProps) {
  const [advancedMode, setAdvancedMode] = useState(false);
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [showConfirmGenerate, setShowConfirmGenerate] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [warnings, setWarnings] = useState<ValidationError[]>([]);

  // Migration assistant state
  const [showMigrationAssistant, setShowMigrationAssistant] = useState(false);
  const [pendingMigrations, setPendingMigrations] = useState<MigrationSuggestion[]>([]);

  // Convert JSON Schema to visual schema for editing
  const visualSchema = useMemo(() => jsonSchemaToVisualSchema(draft.schema), [draft.schema]);

  // JSON string for advanced mode
  const [jsonText, setJsonText] = useState(() =>
    draft.schema ? JSON.stringify(draft.schema, null, 2) : "{}",
  );

  // Sync jsonText when switching to advanced mode
  useEffect(() => {
    if (advancedMode) {
      setJsonText(draft.schema ? JSON.stringify(draft.schema, null, 2) : "{}");
    }
  }, [advancedMode, draft.schema]);

  const handleVisualSchemaChange = useCallback(
    (newVisual: VisualSchema) => {
      const jsonSchema = visualSchemaToJsonSchema(newVisual);
      draft.setDraftSchema(jsonSchema);
      setSaveSuccess(false);
      setSaveError(null);
    },
    [draft],
  );

  const handleJsonChange = useCallback((value: string) => {
    setJsonText(value);
    setSaveSuccess(false);
    setSaveError(null);
    try {
      JSON.parse(value);
      setJsonError(null);
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : "Invalid JSON");
    }
  }, []);

  const handleJsonApply = useCallback(() => {
    try {
      const parsed = JSON.parse(jsonText);
      draft.setDraftSchema(parsed);
      setJsonError(null);
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : "Invalid JSON");
    }
  }, [jsonText, draft]);

  const handleGenerateFromExample = useCallback(() => {
    if (draft.dataExamples.length === 0) return;

    const firstExample = draft.dataExamples[0];
    const generated = generateSchemaFromData(firstExample.data as JsonObject);
    const jsonSchema = visualSchemaToJsonSchema(generated);
    draft.setDraftSchema(jsonSchema);
    setShowConfirmGenerate(false);
  }, [draft]);

  const handleSave = useCallback(
    async (forceUpdate = false) => {
      if (!draft.isSchemaDirty) return;

      // Check for compatibility issues before saving
      if (!forceUpdate && draft.schema && draft.dataExamples.length > 0) {
        const migrationResult = detectMigrations(draft.schema, draft.dataExamples);
        if (!migrationResult.compatible) {
          setPendingMigrations(migrationResult.migrations);
          setShowMigrationAssistant(true);
          return;
        }
      }

      setIsSaving(true);
      setWarnings([]);
      setSaveSuccess(false);
      setSaveError(null);

      try {
        const result = await draft.saveSchema(forceUpdate);
        if (result.success) {
          setSaveSuccess(true);
          if (result.warnings) {
            setWarnings(flattenErrorsByExample(result.warnings));
          }
          // Clear success message after 3 seconds
          setTimeout(() => setSaveSuccess(false), 3000);
        } else {
          // Show error to user
          setSaveError(
            result.error ?? "Failed to save schema. Data examples may not match the new schema.",
          );
        }
      } finally {
        setIsSaving(false);
      }
    },
    [draft],
  );

  const handleApplyMigrations = useCallback(
    (migrations: MigrationSuggestion[]) => {
      // Apply migrations to examples
      const updatedExamples = draft.dataExamples.map((example) => {
        const exampleMigrations = migrations.filter((m) => m.exampleId === example.id);
        if (exampleMigrations.length === 0) return example;

        const updatedData = applyAllMigrations(example.data as JsonObject, exampleMigrations);
        return { ...example, data: updatedData };
      });

      draft.setDraftExamples(updatedExamples);
      setShowMigrationAssistant(false);
      setPendingMigrations([]);

      // Now save the schema (migrations have been applied)
      handleSave(false);
    },
    [draft, handleSave],
  );

  const handleForceSave = useCallback(() => {
    setShowMigrationAssistant(false);
    setPendingMigrations([]);
    handleSave(true);
  }, [handleSave]);

  const extensions = useMemo(() => [javascript()], []);

  const hasExistingSchema = visualSchema.fields.length > 0;
  const hasExamples = draft.dataExamples.length > 0;

  return (
    <div className="space-y-4 p-1">
      {/* Toolbar */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          {hasExamples && (
            <>
              {showConfirmGenerate ? (
                <div className="flex items-center gap-2 text-sm">
                  <AlertTriangle className="h-4 w-4 text-amber-500" />
                  <span>This will replace the current schema.</span>
                  <Button size="sm" variant="destructive" onClick={handleGenerateFromExample}>
                    Replace
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setShowConfirmGenerate(false)}>
                    Cancel
                  </Button>
                </div>
              ) : (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    if (hasExistingSchema) {
                      setShowConfirmGenerate(true);
                    } else {
                      handleGenerateFromExample();
                    }
                  }}
                >
                  <Wand2 className="h-4 w-4 mr-1" />
                  Generate from example
                </Button>
              )}
            </>
          )}
        </div>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={advancedMode}
            onChange={(e) => setAdvancedMode(e.target.checked)}
            className="h-4 w-4 rounded border-gray-300"
          />
          Advanced JSON
        </label>
      </div>

      {/* Success message */}
      {saveSuccess && (
        <div className="flex items-center gap-2 text-sm text-green-600 bg-green-50 p-2 rounded-md">
          <Check className="h-4 w-4" />
          <span>Schema saved successfully</span>
        </div>
      )}

      {/* Save error */}
      {saveError && (
        <div className="flex items-center gap-2 text-sm text-destructive bg-destructive/10 p-2 rounded-md border border-destructive/50">
          <AlertTriangle className="h-4 w-4 flex-shrink-0" />
          <span>{saveError}</span>
        </div>
      )}

      {/* Warnings */}
      {warnings.length > 0 && <ValidationMessages warnings={warnings} />}

      {/* Editor */}
      {advancedMode ? (
        <div className="space-y-2">
          <div
            className={`rounded-md border overflow-hidden ${
              jsonError ? "border-destructive" : "border-input"
            }`}
          >
            <CodeMirror
              value={jsonText}
              onChange={handleJsonChange}
              extensions={extensions}
              height="300px"
              basicSetup={{
                lineNumbers: true,
                foldGutter: true,
                highlightActiveLine: true,
                bracketMatching: true,
                closeBrackets: true,
              }}
            />
          </div>
          {jsonError && <p className="text-xs text-destructive">{jsonError}</p>}
          <Button size="sm" onClick={handleJsonApply} disabled={!!jsonError}>
            Apply JSON
          </Button>
        </div>
      ) : (
        <div className="max-h-80 overflow-auto">
          <SchemaEditor schema={visualSchema} onChange={handleVisualSchemaChange} />
        </div>
      )}

      <DialogFooterActions
        isDirty={draft.isSchemaDirty}
        isSaving={isSaving}
        onSave={() => handleSave(false)}
        onClose={onClose}
        saveLabel="Save Schema"
        disabled={!!jsonError}
      />

      {/* Migration Assistant Dialog */}
      <MigrationAssistant
        open={showMigrationAssistant}
        onOpenChange={setShowMigrationAssistant}
        migrations={pendingMigrations}
        onApplyMigrations={handleApplyMigrations}
        onForceSave={handleForceSave}
        onCancel={() => {
          setShowMigrationAssistant(false);
          setPendingMigrations([]);
        }}
      />
    </div>
  );
}

interface TestDataTabProps {
  draft: ReturnType<typeof useDataContractDraft>;
  onClose: () => void;
}

function TestDataTab({ draft, onClose }: TestDataTabProps) {
  const selectDataExample = useEditorStore((s) => s.selectDataExample);

  const [editingId, setEditingId] = useState<string | null>(
    draft.dataExamples.length > 0 ? draft.dataExamples[0].id : null,
  );
  const [editName, setEditName] = useState("");
  const [editJson, setEditJson] = useState("");
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  const [validationWarnings, setValidationWarnings] = useState<ValidationError[]>([]);
  const [isSaving, setIsSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);

  // Track whether we've initialized to prevent re-loading loops
  const initializedRef = useRef(false);

  // Stable ref for loading example
  const loadExampleRef = useRef<(example: DataExample) => void>(null!);
  loadExampleRef.current = (example: DataExample) => {
    setEditingId(example.id);
    setEditName(example.name);
    setEditJson(JSON.stringify(example.data, null, 2));
    setJsonError(null);
    setNameError(null);
    // Don't clear warnings here - let the validation effect handle it
    setSaveSuccess(false);
  };

  const loadExample = useCallback((example: DataExample) => {
    loadExampleRef.current(example);
  }, []);

  // Initialize editor with first example (only once on mount)
  useEffect(() => {
    if (initializedRef.current) return;

    if (draft.dataExamples.length > 0) {
      loadExampleRef.current(draft.dataExamples[0]);
      initializedRef.current = true;
    }
  }, [draft.dataExamples]);

  const handleSelectExample = useCallback(
    (id: string) => {
      const example = draft.dataExamples.find((e) => e.id === id);
      if (example) {
        loadExample(example);
      }
    },
    [draft.dataExamples, loadExample],
  );

  const handleAddNew = useCallback(() => {
    const newExample: DataExample = {
      id: uuidv4(),
      name: `Example ${draft.dataExamples.length + 1}`,
      data: {},
    };

    draft.addDraftExample(newExample);
    loadExample(newExample);
  }, [draft, loadExample]);

  const handleDelete = useCallback(async () => {
    if (!editingId) return;

    // Use single-example delete endpoint
    const result = await draft.deleteSingleExample(editingId);
    if (!result.success) {
      // Could show an error toast here
      console.error("Failed to delete example");
      return;
    }

    // Select another example or clear
    const remaining = draft.dataExamples.filter((e) => e.id !== editingId);
    if (remaining.length > 0) {
      loadExample(remaining[0]);
    } else {
      setEditingId(null);
      setEditName("");
      setEditJson("");
    }
  }, [editingId, draft, loadExample]);

  const validateAndParseJson = useCallback(
    (json: string): JsonObject | null => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(json);
      } catch (e) {
        setJsonError(e instanceof Error ? e.message : "Invalid JSON");
        // Keep previous warnings visible - don't clear them on JSON syntax error
        return null;
      }

      const zodResult = JsonObjectSchema.safeParse(parsed);
      if (!zodResult.success) {
        setJsonError("Must be a JSON object (not array or primitive)");
        // Keep previous warnings visible
        return null;
      }
      setJsonError(null);

      // Validate against schema if available
      if (draft.schema) {
        const result = validateDataAgainstSchema(zodResult.data, draft.schema);
        if (!result.valid) {
          setValidationWarnings(result.errors.map((e) => ({ path: e.path, message: e.message })));
        } else {
          setValidationWarnings([]);
        }
      } else {
        setValidationWarnings([]);
      }

      return zodResult.data;
    },
    [draft.schema],
  );

  // Validate whenever editJson changes (on load, select, or schema change)
  useEffect(() => {
    if (editJson) {
      validateAndParseJson(editJson);
    } else {
      setValidationWarnings([]);
    }
  }, [editJson, validateAndParseJson]);

  const handleJsonChange = useCallback((value: string) => {
    setEditJson(value);
    setSaveSuccess(false);
    // Validation is handled by the effect above
  }, []);

  const handleSave = useCallback(async () => {
    if (!editingId) return;

    const parsedData = validateAndParseJson(editJson);
    if (!parsedData) return;

    if (!editName.trim()) {
      setNameError("Name is required");
      return;
    }

    setIsSaving(true);
    setSaveSuccess(false);

    try {
      // Check if this is a new example (not yet in store) or an existing one
      const existingInStore = draft.dataExamples.find((e) => e.id === editingId);
      const isNewExample =
        !existingInStore ||
        existingInStore.data === undefined ||
        Object.keys(existingInStore.data).length === 0;

      if (isNewExample) {
        // New example - use batch endpoint to create
        const updatedExamples = draft.dataExamples.map((e) =>
          e.id === editingId ? { ...e, name: editName.trim(), data: parsedData } : e,
        );
        const result = await draft.saveExamples(updatedExamples);
        if (result.success) {
          setSaveSuccess(true);
          selectDataExample(editingId);
          // Sync local state with saved data to clear dirty state
          setEditJson(JSON.stringify(parsedData, null, 2));
          setTimeout(() => setSaveSuccess(false), 3000);
        }
      } else {
        // Existing example - use single-example endpoint
        const result = await draft.saveSingleExample(editingId, {
          name: editName.trim(),
          data: parsedData,
        });

        if (result.success) {
          setSaveSuccess(true);
          selectDataExample(editingId);
          // Sync local state with saved result to clear dirty state
          if (result.example) {
            setEditName(result.example.name);
            setEditJson(JSON.stringify(result.example.data, null, 2));
          }
          setTimeout(() => setSaveSuccess(false), 3000);
        } else if (result.errors) {
          // Validation errors from backend
          const errorMessages = Object.values(result.errors)
            .flat()
            .map((e) => e.message)
            .join(", ");
          setValidationWarnings(Object.values(result.errors).flat());
          console.error("Validation errors:", errorMessages);
        }
      }
    } finally {
      setIsSaving(false);
    }
  }, [draft, editingId, editName, editJson, validateAndParseJson, selectDataExample]);

  const extensions = useMemo(() => [javascript()], []);

  const hasExamples = draft.dataExamples.length > 0;

  // Check if local edits differ from draft (since we don't auto-sync anymore)
  const hasLocalChanges = useMemo(() => {
    if (!editingId) return false;
    const currentExample = draft.dataExamples.find((e) => e.id === editingId);
    if (!currentExample) return true; // New example not yet in draft
    const currentJson = JSON.stringify(currentExample.data, null, 2);
    return currentExample.name !== editName.trim() || currentJson !== editJson;
  }, [editingId, editName, editJson, draft.dataExamples]);

  const isDirty = draft.isExamplesDirty || hasLocalChanges;

  return (
    <div className="space-y-4 p-1">
      {/* Example selector and actions */}
      <div className="flex items-center gap-2">
        <Select value={editingId ?? ""} onValueChange={handleSelectExample} disabled={!hasExamples}>
          <SelectTrigger className="flex-1">
            <SelectValue placeholder="No examples yet" />
          </SelectTrigger>
          <SelectContent>
            {draft.dataExamples.map((example) => (
              <SelectItem key={example.id} value={example.id}>
                {example.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Button variant="outline" size="icon" onClick={handleAddNew}>
          <Plus className="h-4 w-4" />
        </Button>

        <Button
          variant="outline"
          size="icon"
          onClick={handleDelete}
          disabled={!editingId}
          className="text-destructive hover:text-destructive"
        >
          <Trash2 className="h-4 w-4" />
        </Button>
      </div>

      {/* Success message */}
      {saveSuccess && (
        <div className="flex items-center gap-2 text-sm text-green-600 bg-green-50 p-2 rounded-md">
          <Check className="h-4 w-4" />
          <span>Examples saved successfully</span>
        </div>
      )}

      {/* Editor */}
      {hasExamples && editingId ? (
        <div className="space-y-3">
          <div className="space-y-2">
            <Label htmlFor="example-name">Name</Label>
            <Input
              id="example-name"
              value={editName}
              onChange={(e) => {
                setEditName(e.target.value);
                setSaveSuccess(false);
                if (e.target.value.trim()) {
                  setNameError(null);
                }
              }}
              placeholder="Example name"
              className={nameError ? "border-destructive" : ""}
            />
            {nameError && <p className="text-xs text-destructive">{nameError}</p>}
          </div>

          <div className="space-y-2">
            <Label>Data (JSON)</Label>
            <div
              className={`rounded-md border overflow-hidden ${
                jsonError ? "border-destructive" : "border-input"
              }`}
            >
              <CodeMirror
                value={editJson}
                onChange={handleJsonChange}
                extensions={extensions}
                height="200px"
                basicSetup={{
                  lineNumbers: true,
                  foldGutter: true,
                  highlightActiveLine: true,
                  bracketMatching: true,
                  closeBrackets: true,
                }}
              />
            </div>
            {jsonError && <p className="text-xs text-destructive">{jsonError}</p>}

            {/* Schema validation warnings */}
            {validationWarnings.length > 0 && <ValidationMessages warnings={validationWarnings} />}
          </div>
        </div>
      ) : (
        <div className="py-8 text-center text-muted-foreground">
          <p>No data examples yet.</p>
          <p className="text-sm mt-1">
            Click the <Plus className="inline h-4 w-4" /> button to create one.
          </p>
        </div>
      )}

      <DialogFooterActions
        isDirty={isDirty}
        isSaving={isSaving}
        onSave={handleSave}
        onClose={onClose}
        saveLabel="Save Examples"
        disabled={!editingId || !!jsonError || validationWarnings.length > 0}
      />
    </div>
  );
}
