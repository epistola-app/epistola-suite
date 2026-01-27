import { useState, useCallback } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { AlertTriangle } from "lucide-react";
import { SchemaSection } from "@/components/SchemaSection";
import { ExamplesSection } from "@/components/ExamplesSection";
import type { createSchemaManagerStore } from "./store/schemaStore";
import { useDataContractDraft, type SaveCallbacks, type ValidationError } from "./hooks/useDataContractDraft";

export interface SchemaManagerProps {
  store: ReturnType<typeof createSchemaManagerStore>;
  callbacks: SaveCallbacks;
}

/**
 * Main Schema Manager application component
 *
 * Provides a tabbed interface for editing JSON schema and data examples.
 */
export function SchemaManagerApp({ store, callbacks }: SchemaManagerProps) {
  const [activeTab, setActiveTab] = useState<"schema" | "examples">("schema");
  const [isSavingAll, setIsSavingAll] = useState(false);

  // SINGLE shared draft instance for both tabs
  const draft = useDataContractDraft(
    {
      schema: store((s) => s.schema),
      dataExamples: store((s) => s.dataExamples),
      setSchema: store((s) => s.setSchema),
      setDataExamples: store((s) => s.setDataExamples),
    },
    callbacks
  );

  const handleSaveAll = useCallback(async () => {
    setIsSavingAll(true);
    try {
      // Save both schema and examples
      if (draft.isSchemaDirty) {
        await draft.saveSchema(false);
      }
      if (draft.isExamplesDirty) {
        await draft.saveExamples();
      }
    } finally {
      setIsSavingAll(false);
    }
  }, [draft]);

  return (
    <div className="schema-manager-root w-full">
      <Tabs value={activeTab} onValueChange={(val: string) => setActiveTab(val as "schema" | "examples")}>
        <TabsList className="grid w-full grid-cols-2">
          <TabsTrigger value="schema">
            Schema
            {draft.isSchemaDirty && <span className="ml-1 text-amber-500">●</span>}
          </TabsTrigger>
          <TabsTrigger value="examples">
            Test Data
            {draft.isExamplesDirty && <span className="ml-1 text-amber-500">●</span>}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="schema" className="mt-4">
          <SchemaSection draft={draft} />
        </TabsContent>

        <TabsContent value="examples" className="mt-4">
          <ExamplesSection draft={draft} />
        </TabsContent>
      </Tabs>

      {/* Warning banner for unsaved changes */}
      {(draft.isSchemaDirty || draft.isExamplesDirty) && (
        <div className="flex items-center gap-2 p-4 bg-amber-50 border-t border-amber-200 mt-4">
          <AlertTriangle className="h-4 w-4 text-amber-600" />
          <span className="text-sm text-amber-900">
            You have unsaved changes
          </span>
          <Button
            onClick={handleSaveAll}
            size="sm"
            className="ml-auto"
            disabled={isSavingAll}
          >
            {isSavingAll ? "Saving..." : "Save All Changes"}
          </Button>
        </div>
      )}
    </div>
  );
}

// Export type for external use
export type { SaveCallbacks, ValidationError };
