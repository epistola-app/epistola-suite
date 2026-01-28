import { useCallback, useMemo } from "react";
import { AlertTriangle } from "lucide-react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { DataExample } from "../../types/template";
import type { JsonObject } from "../../types/template";
import type { JsonSchema } from "../../types/schema";
import { validateDataAgainstSchema } from "../../utils/schemaValidation";

const DEFAULT_EXAMPLE_VALUE = "__default__" as const;

interface ExampleSelectorProps {
  /** List of available examples to select from */
  examples: DataExample[];
  /** Currently selected example ID (null = use default/built-in) */
  selectedId: string | null;
  /** Optional schema to validate examples against */
  schema?: JsonSchema | null;
  /** Callback when user selects an example */
  onSelect: (id: string | null) => void;
}

/**
 * Simple dropdown selector for choosing which data example to use during template design.
 * Shows validation warnings for examples that don't match the schema.
 *
 * This component only allows SELECTING examples - editing is done at the template level
 * via the schema-manager module.
 */
export function ExampleSelector({ examples, selectedId, schema, onSelect }: ExampleSelectorProps) {
  // Track which examples are invalid against the current schema
  const invalidExampleIds = useMemo(() => {
    if (!schema) return new Set<string>();
    const invalid = new Set<string>();
    for (const example of examples) {
      const result = validateDataAgainstSchema(example.data as JsonObject, schema);
      if (!result.valid) {
        invalid.add(example.id);
      }
    }
    return invalid;
  }, [schema, examples]);

  const handleSelectChange = useCallback(
    (value: string) => {
      if (value === DEFAULT_EXAMPLE_VALUE) {
        onSelect(null);
      } else {
        onSelect(value);
      }
    },
    [onSelect],
  );

  const selectedValue = selectedId ?? DEFAULT_EXAMPLE_VALUE;

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
          {examples.map((example) => {
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
    </div>
  );
}
