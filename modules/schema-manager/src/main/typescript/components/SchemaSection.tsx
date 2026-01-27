import { useState, useCallback, useMemo, useEffect } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { javascript } from "@codemirror/lang-javascript";
import { Wand2, AlertTriangle, Check } from "lucide-react";

import { Button } from "@/components/ui/button";
import { SchemaEditor } from "@/components/schema/SchemaEditor";
import type { DataContractDraft } from "@/hooks/useDataContractDraft";
import {
  ValidationMessages,
  flattenErrorsByExample,
  type ValidationError,
} from "@/components/ValidationMessages";
import { MigrationAssistant } from "@/components/MigrationAssistant";
import type { JsonObject } from "@/types/template";
import type { VisualSchema } from "@/types/schema";
import {
  jsonSchemaToVisualSchema,
  visualSchemaToJsonSchema,
  generateSchemaFromData,
} from "@/utils/schemaUtils";
import {
  detectMigrations,
  applyAllMigrations,
  type MigrationSuggestion,
} from "@/utils/schemaMigration";

interface SchemaSectionProps {
  draft: DataContractDraft;
}

export function SchemaSection({ draft }: SchemaSectionProps) {

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
    async (migrations: MigrationSuggestion[]) => {
      // Apply migrations to examples
      const updatedExamples = draft.dataExamples.map((example) => {
        const exampleMigrations = migrations.filter((m) => m.exampleId === example.id);
        if (exampleMigrations.length === 0) return example;

        const updatedData = applyAllMigrations(example.data as JsonObject, exampleMigrations);
        return { ...example, data: updatedData };
      });

      draft.setDraftExamples(updatedExamples);

      // Save BOTH schema and examples atomically
      setIsSaving(true);
      setSaveSuccess(false);
      setSaveError(null);

      try {
        // Save examples first (with migrated data)
        const examplesResult = await draft.saveExamples(updatedExamples);
        if (!examplesResult.success) {
          setSaveError(examplesResult.error ?? "Failed to save migrated examples");
          return;
        }

        // Then save schema
        const schemaResult = await draft.saveSchema(true); // force = true
        if (schemaResult.success) {
          setSaveSuccess(true);
          setShowMigrationAssistant(false);
          setPendingMigrations([]);
          if (schemaResult.warnings) {
            setWarnings(flattenErrorsByExample(schemaResult.warnings));
          }
          // Clear success message after 3 seconds
          setTimeout(() => setSaveSuccess(false), 3000);
        } else {
          setSaveError(schemaResult.error ?? "Failed to save schema");
        }
      } finally {
        setIsSaving(false);
      }
    },
    [draft],
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
          <AlertTriangle className="h-4 w-4 shrink-0" />
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

      {/* Save button */}
      <div className="flex justify-end">
        <Button onClick={() => handleSave(false)} disabled={!!jsonError || isSaving || !draft.isSchemaDirty}>
          {isSaving ? "Saving..." : "Save Schema"}
        </Button>
      </div>

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
