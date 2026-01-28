import { useState, useEffect } from "react";
import { AlertTriangle, Check, X, Wand2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import type { MigrationSuggestion } from "@/utils/schemaMigration";

interface MigrationAssistantProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  migrations: MigrationSuggestion[];
  onApplyMigrations: (migrations: MigrationSuggestion[]) => void;
  onForceSave: () => void;
  onCancel: () => void;
}

/**
 * Dialog that shows when schema changes would break existing examples.
 * Offers options to auto-fix compatible issues, force save, or cancel.
 */
export function MigrationAssistant({
  open,
  onOpenChange,
  migrations,
  onApplyMigrations,
  onForceSave,
  onCancel,
}: MigrationAssistantProps) {
  const [selectedMigrations, setSelectedMigrations] = useState<Set<string>>(
    () =>
      new Set(migrations.filter((m) => m.autoMigratable).map((m) => `${m.exampleId}:${m.path}`)),
  );

  // Sync selection when migrations prop changes
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- Intentionally syncing state with props
    setSelectedMigrations(
      new Set(migrations.filter((m) => m.autoMigratable).map((m) => `${m.exampleId}:${m.path}`)),
    );
  }, [migrations]);

  const autoMigratableCount = migrations.filter((m) => m.autoMigratable).length;
  const manualCount = migrations.length - autoMigratableCount;

  const handleToggleMigration = (migration: MigrationSuggestion) => {
    const key = `${migration.exampleId}:${migration.path}`;
    const newSelected = new Set(selectedMigrations);
    if (newSelected.has(key)) {
      newSelected.delete(key);
    } else {
      newSelected.add(key);
    }
    setSelectedMigrations(newSelected);
  };

  const handleApplySelected = () => {
    const selected = migrations.filter(
      (m) => m.autoMigratable && selectedMigrations.has(`${m.exampleId}:${m.path}`),
    );
    onApplyMigrations(selected);
  };

  const handleSelectAll = () => {
    setSelectedMigrations(
      new Set(migrations.filter((m) => m.autoMigratable).map((m) => `${m.exampleId}:${m.path}`)),
    );
  };

  const handleSelectNone = () => {
    setSelectedMigrations(new Set());
  };

  // Group migrations by example
  const migrationsByExample = migrations.reduce(
    (acc, migration) => {
      const key = migration.exampleId;
      if (!acc[key]) {
        acc[key] = { name: migration.exampleName, migrations: [] };
      }
      acc[key].migrations.push(migration);
      return acc;
    },
    {} as Record<string, { name: string; migrations: MigrationSuggestion[] }>,
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg max-h-[85vh] overflow-hidden flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <AlertTriangle className="h-5 w-5 text-amber-500" />
            Schema Compatibility Issues
          </DialogTitle>
          <DialogDescription>
            The new schema is incompatible with {migrations.length} field(s) in your test data.
            {autoMigratableCount > 0 && (
              <span className="block mt-1">
                {autoMigratableCount} can be auto-fixed, {manualCount} require manual review.
              </span>
            )}
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 overflow-auto min-h-0 space-y-4 py-4">
          {/* Selection controls */}
          {autoMigratableCount > 0 && (
            <div className="flex items-center gap-2 text-xs">
              <span className="text-muted-foreground">Auto-fix:</span>
              <Button
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-xs"
                onClick={handleSelectAll}
              >
                Select all
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className="h-6 px-2 text-xs"
                onClick={handleSelectNone}
              >
                Select none
              </Button>
            </div>
          )}

          {/* Migration list by example */}
          {Object.entries(migrationsByExample).map(
            ([exampleId, { name, migrations: exampleMigrations }]) => (
              <div key={exampleId} className="border rounded-md p-3 space-y-2">
                <div className="font-medium text-sm">{name}</div>
                <div className="space-y-2">
                  {exampleMigrations.map((migration, i) => (
                    <MigrationItem
                      key={i}
                      migration={migration}
                      selected={selectedMigrations.has(`${migration.exampleId}:${migration.path}`)}
                      onToggle={() => handleToggleMigration(migration)}
                    />
                  ))}
                </div>
              </div>
            ),
          )}
        </div>

        <DialogFooter className="flex-col sm:flex-row gap-2">
          <Button variant="outline" onClick={onCancel}>
            Cancel
          </Button>
          <Button
            variant="outline"
            onClick={onForceSave}
            className="text-amber-600 hover:text-amber-700"
          >
            <AlertTriangle className="h-4 w-4 mr-1" />
            Save Anyway
          </Button>
          {autoMigratableCount > 0 && (
            <Button onClick={handleApplySelected} disabled={selectedMigrations.size === 0}>
              <Wand2 className="h-4 w-4 mr-1" />
              Apply {selectedMigrations.size} Fix{selectedMigrations.size !== 1 ? "es" : ""}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

interface MigrationItemProps {
  migration: MigrationSuggestion;
  selected: boolean;
  onToggle: () => void;
}

function MigrationItem({ migration, selected, onToggle }: MigrationItemProps) {
  const issueLabel = {
    TYPE_MISMATCH: "Type mismatch",
    MISSING_REQUIRED: "Missing required",
    UNKNOWN_FIELD: "Unknown field",
  }[migration.issue];

  return (
    <div
      className={`flex items-start gap-2 p-2 rounded text-xs ${
        migration.autoMigratable
          ? "bg-green-50 border border-green-200 cursor-pointer hover:bg-green-100"
          : "bg-amber-50 border border-amber-200"
      }`}
      onClick={migration.autoMigratable ? onToggle : undefined}
    >
      {migration.autoMigratable ? (
        <div className="mt-0.5">
          {selected ? (
            <Check className="h-4 w-4 text-green-600" />
          ) : (
            <div className="h-4 w-4 border border-gray-300 rounded" />
          )}
        </div>
      ) : (
        <X className="h-4 w-4 text-amber-600 mt-0.5" />
      )}

      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <code className="font-mono text-xs bg-gray-100 px-1 rounded">{migration.path}</code>
          <span className="text-muted-foreground">{issueLabel}</span>
        </div>

        <div className="mt-1 text-muted-foreground">
          {migration.issue === "TYPE_MISMATCH" && (
            <>
              Current:{" "}
              <code className="bg-red-100 px-1 rounded">{formatValue(migration.currentValue)}</code>
              {" → "}
              Expected: <span className="font-medium">{migration.expectedType}</span>
              {migration.autoMigratable && migration.suggestedValue !== null && (
                <>
                  {" → "}
                  Suggested:{" "}
                  <code className="bg-green-100 px-1 rounded">
                    {formatValue(migration.suggestedValue)}
                  </code>
                </>
              )}
            </>
          )}
          {migration.issue === "MISSING_REQUIRED" && <span>Field is required but missing</span>}
        </div>
      </div>
    </div>
  );
}

function formatValue(value: unknown): string {
  if (value === null) return "null";
  if (value === undefined) return "undefined";
  if (typeof value === "string") return `"${value}"`;
  return String(value);
}
