import { useState, useCallback, useMemo, useEffect } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { javascript } from "@codemirror/lang-javascript";
import { Settings2, Plus, Trash2 } from "lucide-react";
import { v4 as uuidv4 } from "uuid";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
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
import { useEditorStore } from "../../store/editorStore";
import type { DataExample, JsonObject } from "../../types/template";

interface DataExamplesManagerProps {
  onSaveDataExamples?: (examples: DataExample[]) => boolean | Promise<boolean>;
}

export function DataExamplesManager({ onSaveDataExamples }: DataExamplesManagerProps) {
  const dataExamples = useEditorStore((s) => s.dataExamples);
  const selectedDataExampleId = useEditorStore((s) => s.selectedDataExampleId);
  const selectDataExample = useEditorStore((s) => s.selectDataExample);

  const [dialogOpen, setDialogOpen] = useState(false);

  const handleSelectChange = useCallback(
    (value: string) => {
      if (value === "__default__") {
        selectDataExample(null);
      } else {
        selectDataExample(value);
      }
    },
    [selectDataExample],
  );

  const selectedValue = selectedDataExampleId ?? "__default__";

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
          <SelectItem value="__default__">Default (Built-in)</SelectItem>
          {dataExamples.map((example) => (
            <SelectItem key={example.id} value={example.id}>
              {example.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogTrigger asChild>
          <Button variant="ghost" size="icon" className="h-7 w-7">
            <Settings2 className="h-4 w-4" />
          </Button>
        </DialogTrigger>
        <DialogContent className="sm:max-w-2xl">
          <DataExamplesDialog
            onSaveDataExamples={onSaveDataExamples}
            onClose={() => setDialogOpen(false)}
          />
        </DialogContent>
      </Dialog>
    </div>
  );
}

interface DataExamplesDialogProps {
  onSaveDataExamples?: (examples: DataExample[]) => boolean | Promise<boolean>;
  onClose: () => void;
}

function DataExamplesDialog({ onSaveDataExamples, onClose }: DataExamplesDialogProps) {
  const dataExamples = useEditorStore((s) => s.dataExamples);
  const addDataExample = useEditorStore((s) => s.addDataExample);
  const updateDataExample = useEditorStore((s) => s.updateDataExample);
  const deleteDataExample = useEditorStore((s) => s.deleteDataExample);
  const selectDataExample = useEditorStore((s) => s.selectDataExample);

  const [editingId, setEditingId] = useState<string | null>(
    dataExamples.length > 0 ? dataExamples[0].id : null,
  );
  const [editName, setEditName] = useState("");
  const [editJson, setEditJson] = useState("");
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);

  // Load the selected example into the editor
  const loadExample = useCallback((example: DataExample) => {
    setEditingId(example.id);
    setEditName(example.name);
    setEditJson(JSON.stringify(example.data, null, 2));
    setJsonError(null);
    setNameError(null);
  }, []);

  // Initialize editor with first example
  useEffect(() => {
    if (dataExamples.length > 0 && !editingId) {
      loadExample(dataExamples[0]);
    } else if (editingId) {
      const example = dataExamples.find((e) => e.id === editingId);
      if (example) {
        loadExample(example);
      }
    }
  }, [dataExamples, editingId, loadExample]);

  const handleSelectExample = useCallback(
    (id: string) => {
      const example = dataExamples.find((e) => e.id === id);
      if (example) {
        loadExample(example);
      }
    },
    [dataExamples, loadExample],
  );

  const handleAddNew = useCallback(() => {
    const newExample: DataExample = {
      id: uuidv4(),
      name: `Example ${dataExamples.length + 1}`,
      data: {},
    };

    // Add locally first - user will edit and then save
    // Backend persistence happens in handleSave after user provides valid data
    addDataExample(newExample);
    loadExample(newExample);
  }, [dataExamples.length, addDataExample, loadExample]);

  const handleDelete = useCallback(async () => {
    if (!editingId) return;

    // Save to backend first
    const updatedExamples = dataExamples.filter((e) => e.id !== editingId);
    if (onSaveDataExamples) {
      const success = await onSaveDataExamples(updatedExamples);
      if (!success) {
        return;
      }
    }

    // Only delete from store after backend confirms
    deleteDataExample(editingId);

    // Select another example or clear
    if (updatedExamples.length > 0) {
      loadExample(updatedExamples[0]);
    } else {
      setEditingId(null);
      setEditName("");
      setEditJson("");
    }
  }, [editingId, dataExamples, deleteDataExample, onSaveDataExamples, loadExample]);

  const validateAndParseJson = useCallback((json: string): JsonObject | null => {
    try {
      const parsed = JSON.parse(json);
      if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
        setJsonError("Must be a JSON object (not array or primitive)");
        return null;
      }
      setJsonError(null);
      return parsed as JsonObject;
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : "Invalid JSON");
      return null;
    }
  }, []);

  const handleJsonChange = useCallback(
    (value: string) => {
      setEditJson(value);
      validateAndParseJson(value);
    },
    [validateAndParseJson],
  );

  const handleSave = useCallback(async () => {
    if (!editingId) return;

    const parsedData = validateAndParseJson(editJson);
    if (!parsedData) return;

    if (!editName.trim()) {
      setNameError("Name is required");
      return;
    }

    setIsSaving(true);

    // Prepare updated list for backend
    const updatedExamples = dataExamples.map((e) =>
      e.id === editingId ? { ...e, name: editName.trim(), data: parsedData } : e,
    );

    // Save to backend first
    if (onSaveDataExamples) {
      const success = await onSaveDataExamples(updatedExamples);
      if (!success) {
        setIsSaving(false);
        return;
      }
    }

    // Only update store after backend confirms
    updateDataExample(editingId, {
      name: editName.trim(),
      data: parsedData,
    });

    // Select the saved example as active test data
    selectDataExample(editingId);

    setIsSaving(false);
    onClose();
  }, [
    editingId,
    editName,
    editJson,
    dataExamples,
    validateAndParseJson,
    updateDataExample,
    onSaveDataExamples,
    selectDataExample,
    onClose,
  ]);

  const extensions = useMemo(() => [javascript()], []);

  const hasExamples = dataExamples.length > 0;

  return (
    <>
      <DialogHeader>
        <DialogTitle>Manage Data Examples</DialogTitle>
        <DialogDescription>
          Create and edit test data for previewing your template with different data.
        </DialogDescription>
      </DialogHeader>

      <div className="space-y-4">
        {/* Example selector and actions */}
        <div className="flex items-center gap-2">
          <Select
            value={editingId ?? ""}
            onValueChange={handleSelectExample}
            disabled={!hasExamples}
          >
            <SelectTrigger className="flex-1">
              <SelectValue placeholder="No examples yet" />
            </SelectTrigger>
            <SelectContent>
              {dataExamples.map((example) => (
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
      </div>

      <DialogFooter>
        <Button variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button onClick={handleSave} disabled={!editingId || !!jsonError || isSaving}>
          {isSaving ? "Saving..." : "Save"}
        </Button>
      </DialogFooter>
    </>
  );
}
