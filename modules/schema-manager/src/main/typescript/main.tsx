import {StrictMode} from "react";
import {createRoot} from "react-dom/client";
import {SchemaManagerApp} from "./App";
import {createSchemaManagerStore} from "./store/schemaStore";
import type {SaveCallbacks} from "./hooks/useDataContractDraft";

import "./index.css";

// Sample data for development
const sampleSchema = {
  type: "object" as const,
  properties: {
    customer: {
      type: "object" as const,
      properties: {
        name: { type: "string" as const },
        email: { type: "string" as const },
      },
      required: ["name", "email"],
    },
    items: {
      type: "array" as const,
      items: {
        type: "object" as const,
        properties: {
          description: { type: "string" as const },
          quantity: { type: "number" as const },
          price: { type: "number" as const },
        },
        required: ["description", "quantity", "price"],
      },
    },
    total: { type: "number" as const },
  },
  required: ["customer", "items", "total"],
};

const sampleExamples = [
  {
    id: "1",
    name: "Sample Invoice",
    data: {
      customer: {
        name: "John Doe",
        email: "john@example.com",
      },
      items: [
        { description: "Widget A", quantity: 2, price: 10.0 },
        { description: "Widget B", quantity: 1, price: 25.0 },
      ],
      total: 45.0,
    },
  },
  {
    id: "2",
    name: "Corporate Invoice",
    data: {
      customer: {
        name: "Acme Corp",
        email: "billing@acme.com",
      },
      items: [
        { description: "Service Fee", quantity: 1, price: 1000.0 },
      ],
      total: 1000.0,
    },
  },
];

// Mock callbacks for development
const callbacks: SaveCallbacks = {
  onSaveSchema: async (schema, forceUpdate) => {
    console.log("Save schema:", schema, "force:", forceUpdate);
    return new Promise((resolve) => {
      setTimeout(() => {
        console.log("Schema saved successfully");
        resolve({ success: true });
      }, 500);
    });
  },

  onSaveDataExamples: async (examples) => {
    console.log("Save examples:", examples);
    return new Promise((resolve) => {
      setTimeout(() => {
        console.log("Examples saved successfully");
        resolve({ success: true });
      }, 500);
    });
  },

  onUpdateDataExample: async (exampleId, updates, forceUpdate) => {
    console.log("Update example:", exampleId, updates, "force:", forceUpdate);
    return new Promise((resolve) => {
      setTimeout(() => {
        console.log("Example updated successfully");
        resolve({
          success: true,
          example: {
            id: exampleId,
            name: updates.name || "Updated",
            data: updates.data || {},
          },
        });
      }, 500);
    });
  },

  onDeleteDataExample: async (exampleId) => {
    console.log("Delete example:", exampleId);
    return new Promise((resolve) => {
      setTimeout(() => {
        console.log("Example deleted successfully");
        resolve({ success: true });
      }, 500);
    });
  },

  onValidateSchema: async (schema, examples) => {
    console.log("Validate schema:", schema, "against examples:", examples);
    return new Promise((resolve) => {
      setTimeout(() => {
        console.log("Validation complete");
        resolve({ compatible: true, errors: [], migrations: [] });
      }, 300);
    });
  },
};

// Create store
const store = createSchemaManagerStore({
  schema: sampleSchema,
  dataExamples: sampleExamples,
  templateId: 1,
});

// Mount app
const root = createRoot(document.getElementById("root")!);
root.render(
  <StrictMode>
    <SchemaManagerApp store={store} callbacks={callbacks} />
  </StrictMode>
);
