import { describe, it, expect } from "vitest";
import { validateDataAgainstSchema, formatValidationErrors } from "./schemaValidation";
import type { JsonSchema } from "../types/schema";
import type { JsonObject } from "../types/template";

describe("validateDataAgainstSchema", () => {
  it("returns valid for null schema", () => {
    const data: JsonObject = { name: "John" };

    const result = validateDataAgainstSchema(data, null);

    expect(result.valid).toBe(true);
    expect(result.errors).toHaveLength(0);
  });

  it("returns valid for non-object schema", () => {
    const data: JsonObject = { name: "John" };
    const schema = { type: "array" } as unknown as JsonSchema;

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(true);
  });

  it("validates required fields", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        name: { type: "string" },
        email: { type: "string" },
      },
      required: ["name", "email"],
    };
    const data: JsonObject = { name: "John" };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(false);
    expect(result.errors).toHaveLength(1);
    expect(result.errors[0].path).toBe("$.email");
    expect(result.errors[0].message).toBe("is required");
  });

  it("validates string type", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        name: { type: "string" },
      },
    };
    const data: JsonObject = { name: 123 };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(false);
    expect(result.errors[0].message).toContain("must be string");
  });

  it("validates number type", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        price: { type: "number" },
      },
    };
    const data: JsonObject = { price: "expensive" };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(false);
    expect(result.errors[0].message).toContain("must be number");
  });

  it("validates integer type", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        count: { type: "integer" },
      },
    };
    const data: JsonObject = { count: 3.14 };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(false);
    expect(result.errors[0].message).toContain("must be integer");
  });

  it("allows integer for number type", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        value: { type: "number" },
      },
    };
    const data: JsonObject = { value: 42 };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(true);
  });

  it("validates boolean type", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        active: { type: "boolean" },
      },
    };
    const data: JsonObject = { active: "yes" };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(false);
    expect(result.errors[0].message).toContain("must be boolean");
  });

  it("validates array type", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        items: { type: "array", items: { type: "string" } },
      },
    };
    const data: JsonObject = { items: "not-an-array" };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(false);
    expect(result.errors[0].message).toContain("must be array");
  });

  it("validates array items", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        numbers: { type: "array", items: { type: "integer" } },
      },
    };
    const data: JsonObject = { numbers: [1, "two", 3] };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(false);
    expect(result.errors[0].path).toBe("$.numbers[1]");
  });

  it("validates nested objects", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        user: {
          type: "object",
          properties: {
            name: { type: "string" },
          },
          required: ["name"],
        },
      },
    };
    const data: JsonObject = { user: {} };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(false);
    expect(result.errors[0].path).toBe("$.user.name");
  });

  it("validates deeply nested objects", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        level1: {
          type: "object",
          properties: {
            level2: {
              type: "object",
              properties: {
                value: { type: "integer" },
              },
            },
          },
        },
      },
    };
    const data: JsonObject = { level1: { level2: { value: "not-int" } } };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(false);
    expect(result.errors[0].path).toBe("$.level1.level2.value");
  });

  it("allows null values", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        optional: { type: "string" },
      },
    };
    const data: JsonObject = { optional: null };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(true);
  });

  it("handles union types (picks first matching)", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        value: { type: ["string", "integer"] },
      },
    };

    expect(validateDataAgainstSchema({ value: "text" }, schema).valid).toBe(true);
    expect(validateDataAgainstSchema({ value: 42 }, schema).valid).toBe(true);
    expect(validateDataAgainstSchema({ value: true }, schema).valid).toBe(false);
  });

  it("returns valid for data with extra properties", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: {
        name: { type: "string" },
      },
    };
    const data: JsonObject = { name: "John", extra: "field" };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(true);
  });

  it("validates complex nested structure", () => {
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
            required: ["name"],
          },
        },
      },
      required: ["items"],
    };
    const data: JsonObject = {
      items: [
        { name: "Item 1", price: 100 },
        { price: 200 }, // missing name
      ],
    };

    const result = validateDataAgainstSchema(data, schema);

    expect(result.valid).toBe(false);
    expect(result.errors[0].path).toBe("$.items[1].name");
  });
});

describe("formatValidationErrors", () => {
  it("formats errors as path: message", () => {
    const errors = [
      { path: "$.name", message: "is required" },
      { path: "$.age", message: "must be integer" },
    ];

    const formatted = formatValidationErrors(errors);

    expect(formatted).toEqual(["$.name: is required", "$.age: must be integer"]);
  });

  it("returns empty array for no errors", () => {
    const formatted = formatValidationErrors([]);

    expect(formatted).toEqual([]);
  });
});
