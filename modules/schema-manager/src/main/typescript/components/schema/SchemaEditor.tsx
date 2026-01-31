import {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {ChevronDown, ChevronRight, Plus, Trash2} from "lucide-react";
import {v4 as uuidv4} from "uuid";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from "@/components/ui/select";
import {cn} from "@/lib/utils";
import type {SchemaField, SchemaFieldType, SchemaFieldUpdate, VisualSchema,} from "../../types/schema";
import {applyFieldUpdate, createEmptyField, FIELD_TYPE_LABELS} from "../../utils/schemaUtils";

interface SchemaEditorProps {
  schema: VisualSchema;
  onChange: (schema: VisualSchema) => void;
  disabled?: boolean;
}

export function SchemaEditor({ schema, onChange, disabled = false }: SchemaEditorProps) {
  const fieldCounterRef = useRef<string | null>(null);

  const handleAddField = useCallback(() => {
    fieldCounterRef.current = uuidv4();
    const newField = createEmptyField(`field${fieldCounterRef.current}`);
    onChange({ fields: [...schema.fields, newField] });
  }, [schema.fields, onChange]);

  const handleUpdateField = useCallback(
    (id: string, updates: SchemaFieldUpdate) => {
      onChange({
        fields: schema.fields.map((field) =>
          field.id === id ? applyFieldUpdate(field, updates) : field,
        ),
      });
    },
    [schema.fields, onChange],
  );

  const handleDeleteField = useCallback(
    (id: string) => {
      onChange({
        fields: schema.fields.filter((field) => field.id !== id),
      });
    },
    [schema.fields, onChange],
  );

  return (
    <div className="space-y-3">
      {schema.fields.length === 0 ? (
        <div className="py-6 text-center text-muted-foreground">
          <p>No fields defined yet.</p>
          <p className="text-sm mt-1">Add fields to define your data contract.</p>
        </div>
      ) : (
        <div className="space-y-2">
          {schema.fields.map((field) => (
            <SchemaFieldRow
              key={field.id}
              field={field}
              onUpdate={(updates) => handleUpdateField(field.id, updates)}
              onDelete={() => handleDeleteField(field.id)}
              disabled={disabled}
            />
          ))}
        </div>
      )}

      <Button
        variant="outline"
        size="sm"
        onClick={handleAddField}
        disabled={disabled}
        className="w-full"
      >
        <Plus className="h-4 w-4 mr-1" />
        Add Field
      </Button>
    </div>
  );
}

interface SchemaFieldRowProps {
  field: SchemaField;
  onUpdate: (updates: SchemaFieldUpdate) => void;
  onDelete: () => void;
  disabled?: boolean;
  isNested?: boolean;
}

function SchemaFieldRow({
  field,
  onUpdate,
  onDelete,
  disabled = false,
  isNested = false,
}: SchemaFieldRowProps) {
  const [expanded, setExpanded] = useState(false);
  const [localName, setLocalName] = useState(field.name);
  const nestedCounterRef = useRef<string | null>(null);

  // Sync local state when field.name changes externally
  useEffect(() => {
    setLocalName(field.name);
  }, [field.name]);

  const showExpandButton =
    field.type === "object" || (field.type === "array" && field.arrayItemType === "object");

  const handleTypeChange = useCallback(
    (type: SchemaFieldType) => {
      const updates: SchemaFieldUpdate = { type };

      // Reset nested fields when type changes
      if (type !== "object" && type !== "array") {
        updates.nestedFields = undefined;
        updates.arrayItemType = undefined;
      }

      // Set default array item type
      if (type === "array") {
        updates.arrayItemType = "string";
      }

      // Initialize nested fields for object type
      if (type === "object") {
        updates.nestedFields = [];
      }

      onUpdate(updates);
    },
    [onUpdate],
  );

  const handleArrayItemTypeChange = useCallback(
    (itemType: SchemaFieldType) => {
      const updates: SchemaFieldUpdate = { arrayItemType: itemType };

      if (itemType === "object") {
        updates.nestedFields = [];
      } else {
        updates.nestedFields = undefined;
      }

      onUpdate(updates);
    },
    [onUpdate],
  );

  // Get nestedFields safely based on field type
  const nestedFields = useMemo(() => {
    if (field.type === "object" || field.type === "array") {
      return field.nestedFields || [];
    }
    return [];
  }, [field]);

  const handleAddNestedField = useCallback(() => {
    nestedCounterRef.current = uuidv4();
    const newField = createEmptyField(`${field.name}_field${nestedCounterRef.current}`);
    onUpdate({ nestedFields: [...nestedFields, newField] });
    setExpanded(true);
  }, [field.name, nestedFields, onUpdate]);

  const handleUpdateNestedField = useCallback(
    (id: string, updates: SchemaFieldUpdate) => {
      onUpdate({
        nestedFields: nestedFields.map((f) => (f.id === id ? applyFieldUpdate(f, updates) : f)),
      });
    },
    [nestedFields, onUpdate],
  );

  const handleDeleteNestedField = useCallback(
    (id: string) => {
      onUpdate({
        nestedFields: nestedFields.filter((f) => f.id !== id),
      });
    },
    [nestedFields, onUpdate],
  );

  return (
    <div
      className={cn(
        "rounded-md border bg-background",
        isNested ? "ml-6 border-dashed" : "border-input",
      )}
    >
      <div className="flex items-center gap-2 p-2">
        {/* Expand/collapse button for nested content */}
        {showExpandButton && !isNested ? (
          <button
            type="button"
            onClick={() => setExpanded(!expanded)}
            className="p-0.5 hover:bg-muted rounded"
            disabled={disabled}
          >
            {expanded ? (
              <ChevronDown className="h-4 w-4 text-muted-foreground" />
            ) : (
              <ChevronRight className="h-4 w-4 text-muted-foreground" />
            )}
          </button>
        ) : (
          <div className="w-5" />
        )}

        {/* Field name */}
        <Input
          value={localName}
          onChange={(e) => setLocalName(e.target.value)}
          onBlur={(e) => {
            const trimmed = e.target.value.trim();
            if (trimmed && trimmed !== field.name) {
              onUpdate({ name: trimmed });
            } else if (!trimmed) {
              // Revert to original if empty
              setLocalName(field.name);
            }
          }}
          placeholder="Field name"
          className="h-8 w-32 text-sm"
          disabled={disabled}
        />

        {/* Type selector */}
        <Select
          value={field.type}
          onValueChange={(value) => handleTypeChange(value as SchemaFieldType)}
          disabled={disabled}
        >
          <SelectTrigger className="h-8 w-28 text-sm">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {Object.entries(FIELD_TYPE_LABELS).map(([value, label]) => (
              <SelectItem key={value} value={value}>
                {label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {/* Array item type selector */}
        {field.type === "array" && (
          <Select
            value={field.arrayItemType || "string"}
            onValueChange={(value) => handleArrayItemTypeChange(value as SchemaFieldType)}
            disabled={disabled}
          >
            <SelectTrigger className="h-8 w-28 text-sm">
              <SelectValue placeholder="Item type" />
            </SelectTrigger>
            <SelectContent>
              {Object.entries(FIELD_TYPE_LABELS)
                .filter(([value]) => value !== "array")
                .map(([value, label]) => (
                  <SelectItem key={value} value={value}>
                    {label}
                  </SelectItem>
                ))}
            </SelectContent>
          </Select>
        )}

        {/* Required toggle */}
        <label className="flex items-center gap-1.5 text-sm cursor-pointer">
          <input
            type="checkbox"
            checked={field.required}
            onChange={(e) => onUpdate({ required: e.target.checked })}
            disabled={disabled}
            className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
          />
          <span className="text-muted-foreground">Required</span>
        </label>

        {/* Add nested field button */}
        {showExpandButton && !isNested && (
          <Button
            variant="ghost"
            size="icon-xs"
            onClick={handleAddNestedField}
            disabled={disabled}
            title="Add nested field"
          >
            <Plus className="h-3 w-3" />
          </Button>
        )}

        {/* Delete button */}
        <Button
          variant="ghost"
          size="icon-xs"
          onClick={onDelete}
          disabled={disabled}
          className="text-destructive hover:text-destructive ml-auto"
        >
          <Trash2 className="h-3 w-3" />
        </Button>
      </div>

      {/* Nested fields */}
      {expanded && showExpandButton && !isNested && (
        <div className="border-t px-2 py-2 space-y-2 bg-muted/30">
          {nestedFields.length === 0 ? (
            <p className="text-xs text-muted-foreground text-center py-2">
              No nested fields. Click + to add.
            </p>
          ) : (
            nestedFields.map((nestedField) => (
              <SchemaFieldRow
                key={nestedField.id}
                field={nestedField}
                onUpdate={(updates) => handleUpdateNestedField(nestedField.id, updates)}
                onDelete={() => handleDeleteNestedField(nestedField.id)}
                disabled={disabled}
                isNested={true}
              />
            ))
          )}
        </div>
      )}
    </div>
  );
}
