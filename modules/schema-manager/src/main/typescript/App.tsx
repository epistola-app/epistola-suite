import { useState } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { SchemaSection } from "@/components/SchemaSection";
import { ExamplesSection } from "@/components/ExamplesSection";
import type { createSchemaManagerStore } from "./store/schemaStore";
import type { SaveCallbacks, ValidationError } from "./hooks/useDataContractDraft";

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

  return (
    <div className="schema-manager-root w-full">
      <Tabs value={activeTab} onValueChange={(val: string) => setActiveTab(val as "schema" | "examples")}>
        <TabsList className="grid w-full grid-cols-2">
          <TabsTrigger value="schema">Schema</TabsTrigger>
          <TabsTrigger value="examples">Test Data</TabsTrigger>
        </TabsList>

        <TabsContent value="schema" className="mt-4">
          <SchemaSection store={store} callbacks={callbacks} />
        </TabsContent>

        <TabsContent value="examples" className="mt-4">
          <ExamplesSection store={store} callbacks={callbacks} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

// Export type for external use
export type { SaveCallbacks, ValidationError };
