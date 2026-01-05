import { describe, it, expect } from "vitest";
import {
  SchemaFieldTypeSchema,
  PrimitiveFieldTypeSchema,
  PrimitiveFieldSchema,
  SchemaFieldSchema,
  SchemaFieldUpdateSchema,
  VisualSchemaSchema,
  JsonSchemaPropertySchema,
  JsonSchemaSchema,
  SchemaIssueSchema,
} from "./schema";

describe("SchemaFieldTypeSchema", () => {
  it("accepts valid field types", () => {
    expect(SchemaFieldTypeSchema.parse("string")).toBe("string");
    expect(SchemaFieldTypeSchema.parse("number")).toBe("number");
    expect(SchemaFieldTypeSchema.parse("integer")).toBe("integer");
    expect(SchemaFieldTypeSchema.parse("boolean")).toBe("boolean");
    expect(SchemaFieldTypeSchema.parse("array")).toBe("array");
    expect(SchemaFieldTypeSchema.parse("object")).toBe("object");
  });

  it("rejects invalid field types", () => {
    expect(() => SchemaFieldTypeSchema.parse("invalid")).toThrow();
    expect(() => SchemaFieldTypeSchema.parse("")).toThrow();
    expect(() => SchemaFieldTypeSchema.parse(123)).toThrow();
  });
});

describe("PrimitiveFieldTypeSchema", () => {
  it("accepts primitive types only", () => {
    expect(PrimitiveFieldTypeSchema.parse("string")).toBe("string");
    expect(PrimitiveFieldTypeSchema.parse("number")).toBe("number");
    expect(PrimitiveFieldTypeSchema.parse("integer")).toBe("integer");
    expect(PrimitiveFieldTypeSchema.parse("boolean")).toBe("boolean");
  });

  it("rejects container types", () => {
    expect(() => PrimitiveFieldTypeSchema.parse("array")).toThrow();
    expect(() => PrimitiveFieldTypeSchema.parse("object")).toThrow();
  });
});

describe("PrimitiveFieldSchema", () => {
  it("validates a primitive field", () => {
    const field = {
      id: "field-1",
      name: "userName",
      type: "string",
      required: true,
    };
    expect(PrimitiveFieldSchema.parse(field)).toEqual(field);
  });

  it("accepts optional description", () => {
    const field = {
      id: "field-1",
      name: "age",
      type: "integer",
      required: false,
      description: "User age in years",
    };
    expect(PrimitiveFieldSchema.parse(field)).toEqual(field);
  });

  it("rejects container types", () => {
    const field = {
      id: "field-1",
      name: "items",
      type: "array",
      required: false,
    };
    expect(() => PrimitiveFieldSchema.parse(field)).toThrow();
  });
});

describe("SchemaFieldSchema (discriminated union)", () => {
  it("validates primitive field", () => {
    const field = {
      id: "f1",
      name: "title",
      type: "string",
      required: true,
    };
    const result = SchemaFieldSchema.parse(field);
    expect(result.type).toBe("string");
  });

  it("validates array field with item type", () => {
    const field = {
      id: "f2",
      name: "tags",
      type: "array",
      arrayItemType: "string",
      required: false,
    };
    const result = SchemaFieldSchema.parse(field);
    expect(result.type).toBe("array");
    if (result.type === "array") {
      expect(result.arrayItemType).toBe("string");
    }
  });

  it("validates array field with nested object items", () => {
    const field = {
      id: "f3",
      name: "items",
      type: "array",
      arrayItemType: "object",
      required: true,
      nestedFields: [
        { id: "n1", name: "name", type: "string", required: true },
        { id: "n2", name: "price", type: "number", required: true },
      ],
    };
    const result = SchemaFieldSchema.parse(field);
    expect(result.type).toBe("array");
    if (result.type === "array") {
      expect(result.nestedFields).toHaveLength(2);
    }
  });

  it("validates object field with nested fields", () => {
    const field = {
      id: "f4",
      name: "address",
      type: "object",
      required: false,
      nestedFields: [
        { id: "n1", name: "street", type: "string", required: true },
        { id: "n2", name: "city", type: "string", required: true },
      ],
    };
    const result = SchemaFieldSchema.parse(field);
    expect(result.type).toBe("object");
    if (result.type === "object") {
      expect(result.nestedFields).toHaveLength(2);
    }
  });

  it("validates object field without nested fields", () => {
    const field = {
      id: "f5",
      name: "metadata",
      type: "object",
      required: false,
    };
    const result = SchemaFieldSchema.parse(field);
    expect(result.type).toBe("object");
  });
});

describe("SchemaFieldUpdateSchema", () => {
  it("accepts partial updates", () => {
    expect(SchemaFieldUpdateSchema.parse({ name: "newName" })).toEqual({ name: "newName" });
    expect(SchemaFieldUpdateSchema.parse({ type: "number" })).toEqual({ type: "number" });
    expect(SchemaFieldUpdateSchema.parse({ required: true })).toEqual({ required: true });
  });

  it("accepts empty object", () => {
    expect(SchemaFieldUpdateSchema.parse({})).toEqual({});
  });

  it("accepts arrayItemType update", () => {
    const update = { arrayItemType: "object" };
    expect(SchemaFieldUpdateSchema.parse(update)).toEqual(update);
  });

  it("accepts nestedFields update", () => {
    const update = {
      nestedFields: [{ id: "n1", name: "field", type: "string", required: false }],
    };
    expect(SchemaFieldUpdateSchema.parse(update)).toEqual(update);
  });
});

describe("VisualSchemaSchema", () => {
  it("validates empty schema", () => {
    const schema = { fields: [] };
    expect(VisualSchemaSchema.parse(schema)).toEqual(schema);
  });

  it("validates schema with multiple fields", () => {
    const schema = {
      fields: [
        { id: "f1", name: "name", type: "string", required: true },
        { id: "f2", name: "age", type: "integer", required: false },
        {
          id: "f3",
          name: "items",
          type: "array",
          arrayItemType: "string",
          required: false,
        },
      ],
    };
    const result = VisualSchemaSchema.parse(schema);
    expect(result.fields).toHaveLength(3);
  });
});

describe("JsonSchemaPropertySchema", () => {
  it("validates simple property", () => {
    const prop = { type: "string" };
    expect(JsonSchemaPropertySchema.parse(prop)).toEqual(prop);
  });

  it("validates property with description", () => {
    const prop = { type: "string", description: "User name" };
    expect(JsonSchemaPropertySchema.parse(prop)).toEqual(prop);
  });

  it("validates array property with items", () => {
    const prop = {
      type: "array",
      items: { type: "string" },
    };
    expect(JsonSchemaPropertySchema.parse(prop)).toEqual(prop);
  });

  it("validates object property with properties", () => {
    const prop = {
      type: "object",
      properties: {
        name: { type: "string" },
        age: { type: "integer" },
      },
      required: ["name"],
    };
    expect(JsonSchemaPropertySchema.parse(prop)).toEqual(prop);
  });

  it("validates union type with supported types", () => {
    // Note: Our schema only supports standard JSON Schema types, not "null"
    const prop = { type: ["string", "integer"] };
    expect(JsonSchemaPropertySchema.parse(prop)).toEqual(prop);
  });
});

describe("JsonSchemaSchema", () => {
  it("validates minimal JSON Schema", () => {
    const schema = { type: "object" };
    expect(JsonSchemaSchema.parse(schema)).toEqual(schema);
  });

  it("validates full JSON Schema", () => {
    const schema = {
      $schema: "http://json-schema.org/draft-07/schema#",
      type: "object",
      properties: {
        name: { type: "string" },
        items: {
          type: "array",
          items: { type: "object", properties: { id: { type: "integer" } } },
        },
      },
      required: ["name"],
      additionalProperties: false,
    };
    const result = JsonSchemaSchema.parse(schema);
    expect(result.type).toBe("object");
    expect(result.properties?.name.type).toBe("string");
  });

  it("rejects non-object root type", () => {
    const schema = { type: "array" };
    expect(() => JsonSchemaSchema.parse(schema)).toThrow();
  });
});

describe("SchemaIssueSchema", () => {
  it("validates issue with all types", () => {
    expect(
      SchemaIssueSchema.parse({ type: "missing", path: "$.field", message: "Field missing" }),
    ).toBeTruthy();
    expect(
      SchemaIssueSchema.parse({ type: "removed", path: "$.old", message: "Field removed" }),
    ).toBeTruthy();
    expect(
      SchemaIssueSchema.parse({
        type: "type_mismatch",
        path: "$.age",
        message: "Expected number",
      }),
    ).toBeTruthy();
  });

  it("rejects invalid issue type", () => {
    expect(() =>
      SchemaIssueSchema.parse({ type: "invalid", path: "$.field", message: "Error" }),
    ).toThrow();
  });
});
