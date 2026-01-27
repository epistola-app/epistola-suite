import { useState, useCallback, useMemo, useRef, useEffect } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { javascript } from "@codemirror/lang-javascript";
import { Plus, Trash2, Check } from "lucide-react";
import { v4 as uuidv4 } from "uuid";

import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  useDataContractDraft,
  type SaveCallbacks,
} from "@/hooks/useDataContractDraft";
import {
  ValidationMessages,
  type ValidationError,
} from "@/components/ValidationMessages";
import type { DataExample, JsonObject } from "@/types/template";
import { JsonObjectSchema } from "@/types/template";
import { validateDataAgainstSchema } from "@/utils/schemaValidation";
import type { createSchemaManagerStore } from "@/store/schemaStore";

interface ExamplesSectionProps {
  store: ReturnType<typeof createSchemaManagerStore>;
  callbacks: SaveCallbacks;
}

export function ExamplesSection({ store, callbacks }: ExamplesSectionProps) {
  const draft = useDataContractDraft(
    {
      schema: store((s) => s.schema),
      dataExamples: store((s) => s.dataExamples),
      setSchema: store((s) => s.setSchema),
      setDataExamples: store((s) => s.setDataExamples),
    },
    callbacks,
  );

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
  }, [draft, editingId, editName, editJson, validateAndParseJson]);

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

      {/* Save button */}
      <div className="flex justify-end">
        <Button
          onClick={handleSave}
          disabled={!editingId || !!jsonError || validationWarnings.length > 0 || isSaving || !isDirty}
        >
          {isSaving ? "Saving..." : "Save Examples"}
        </Button>
      </div>
    </div>
  );
}
