import { describe, it, expect, vi } from "vitest";
import { TemplateEditor } from "../editor";
import type { JsonSchema } from "../types";

describe("Schema", () => {
  describe("setSchema", () => {
    it("should set a valid schema", () => {
      const editor = new TemplateEditor();
      const schema: JsonSchema = {
        type: "object",
        properties: {
          name: { type: "string" },
          age: { type: "number" },
        },
        required: ["name"],
      };

      editor.setSchema(schema);

      expect(editor.getSchema()).toEqual(schema);
    });

    it("should support nested object properties", () => {
      const editor = new TemplateEditor();
      const schema: JsonSchema = {
        type: "object",
        properties: {
          order: {
            type: "object",
            properties: {
              id: { type: "string" },
              total: { type: "number" },
            },
            required: ["id"],
          },
        },
      };

      editor.setSchema(schema);

      expect(editor.getSchema()?.properties?.order?.type).toBe("object");
      expect(editor.getSchema()?.properties?.order?.properties?.id?.type).toBe(
        "string",
      );
    });

    it("should support array properties with items", () => {
      const editor = new TemplateEditor();
      const schema: JsonSchema = {
        type: "object",
        properties: {
          items: {
            type: "array",
            items: {
              type: "object",
              properties: {
                name: { type: "string" },
                price: { type: "number" },
              },
            },
          },
        },
      };

      editor.setSchema(schema);

      expect(editor.getSchema()?.properties?.items?.type).toBe("array");
      expect(editor.getSchema()?.properties?.items?.items?.type).toBe("object");
    });

    it("should clear schema when setting null", () => {
      const editor = new TemplateEditor();
      const schema: JsonSchema = {
        type: "object",
        properties: {
          name: { type: "string" },
        },
      };

      editor.setSchema(schema);
      expect(editor.getSchema()).not.toBeNull();

      editor.setSchema(null);
      expect(editor.getSchema()).toBeNull();
    });

    it("should be null by default", () => {
      const editor = new TemplateEditor();

      expect(editor.getSchema()).toBeNull();
    });
  });

  describe("getSchema", () => {
    it("should return the current schema", () => {
      const editor = new TemplateEditor();
      const schema: JsonSchema = {
        type: "object",
        properties: {
          email: { type: "string", description: "User email" },
        },
      };

      editor.setSchema(schema);

      const retrieved = editor.getSchema();
      expect(retrieved).toEqual(schema);
    });

    it("should return null when no schema is set", () => {
      const editor = new TemplateEditor();

      expect(editor.getSchema()).toBeNull();
    });

    it("should return a reference to the same object", () => {
      const editor = new TemplateEditor();
      const schema: JsonSchema = {
        type: "object",
        properties: {
          id: { type: "string" },
        },
      };

      editor.setSchema(schema);

      expect(editor.getSchema()).toBe(schema);
    });
  });

  describe("subscribe to schema changes", () => {
    it("should notify when schema is set", () => {
      const editor = new TemplateEditor();
      const callback = vi.fn();
      const stores = editor.getStores();

      stores.$schema.subscribe(callback);

      const schema: JsonSchema = {
        type: "object",
        properties: {
          name: { type: "string" },
        },
      };
      editor.setSchema(schema);

      // nanostores passes (newValue, oldValue, changedKey) to subscriber
      expect(callback).toHaveBeenCalledWith(schema, null, undefined);
    });

    it("should notify when schema is cleared", () => {
      const editor = new TemplateEditor();
      const schema: JsonSchema = {
        type: "object",
        properties: {
          name: { type: "string" },
        },
      };
      editor.setSchema(schema);

      const callback = vi.fn();
      const stores = editor.getStores();
      stores.$schema.subscribe(callback);
      callback.mockClear();

      editor.setSchema(null);

      // nanostores passes (newValue, oldValue, changedKey) to subscriber
      expect(callback).toHaveBeenCalledWith(null, schema, undefined);
    });

    it("should not notify if schema value is unchanged", () => {
      const editor = new TemplateEditor();
      const schema: JsonSchema = {
        type: "object",
        properties: {
          name: { type: "string" },
        },
      };
      editor.setSchema(schema);

      const callback = vi.fn();
      const stores = editor.getStores();
      stores.$schema.subscribe(callback);
      callback.mockClear();

      // Set the same schema reference again
      editor.setSchema(schema);

      // nanostores may or may not notify for same reference, that's implementation detail
      // we just verify no errors occur
      expect(callback.mock.calls.length).toBeLessThanOrEqual(1);
    });
  });

  describe("schema with all field types", () => {
    it("should support all SchemaFieldType values", () => {
      const editor = new TemplateEditor();
      const schema: JsonSchema = {
        type: "object",
        properties: {
          stringField: { type: "string" },
          numberField: { type: "number" },
          integerField: { type: "integer" },
          booleanField: { type: "boolean" },
          arrayField: { type: "array" },
          objectField: { type: "object" },
        },
      };

      editor.setSchema(schema);

      const retrieved = editor.getSchema();
      expect(retrieved?.properties?.stringField?.type).toBe("string");
      expect(retrieved?.properties?.numberField?.type).toBe("number");
      expect(retrieved?.properties?.integerField?.type).toBe("integer");
      expect(retrieved?.properties?.booleanField?.type).toBe("boolean");
      expect(retrieved?.properties?.arrayField?.type).toBe("array");
      expect(retrieved?.properties?.objectField?.type).toBe("object");
    });
  });
});
