import { describe, it, expect } from "vitest";
import { analyzeSchemaImpact, detectRemovedPaths, getExpressionCoverage } from "./impactAnalysis";
import type { VisualSchema } from "../types/schema";

describe("analyzeSchemaImpact", () => {
  it("returns no issues when all expressions match schema", () => {
    const schema: VisualSchema = {
      fields: [
        { id: "1", name: "customer", type: "object", required: false, nestedFields: [
          { id: "2", name: "name", type: "string", required: true },
        ]},
        { id: "3", name: "total", type: "number", required: false },
      ],
    };
    const expressions = new Set(["customer.name", "total"]);

    const issues = analyzeSchemaImpact(schema, expressions);

    expect(issues).toHaveLength(0);
  });

  it("returns issues for missing paths", () => {
    const schema: VisualSchema = {
      fields: [
        { id: "1", name: "customer", type: "string", required: false },
      ],
    };
    const expressions = new Set(["unknown.field", "another.missing"]);

    const issues = analyzeSchemaImpact(schema, expressions);

    expect(issues).toHaveLength(2);
    expect(issues[0].type).toBe("missing");
    expect(issues[0].path).toBe("unknown.field");
    expect(issues[1].path).toBe("another.missing");
  });

  it("handles empty schema", () => {
    const schema: VisualSchema = { fields: [] };
    const expressions = new Set(["any.path"]);

    const issues = analyzeSchemaImpact(schema, expressions);

    expect(issues).toHaveLength(1);
    expect(issues[0].type).toBe("missing");
  });

  it("handles empty expressions", () => {
    const schema: VisualSchema = {
      fields: [{ id: "1", name: "customer", type: "string", required: false }],
    };

    const issues = analyzeSchemaImpact(schema, new Set());

    expect(issues).toHaveLength(0);
  });
});

describe("detectRemovedPaths", () => {
  it("detects removed paths that are used in expressions", () => {
    const oldSchema: VisualSchema = {
      fields: [
        { id: "1", name: "customer", type: "string", required: false },
        { id: "2", name: "legacyField", type: "string", required: false },
      ],
    };
    const newSchema: VisualSchema = {
      fields: [
        { id: "1", name: "customer", type: "string", required: false },
      ],
    };
    const expressions = new Set(["customer", "legacyField"]);

    const issues = detectRemovedPaths(oldSchema, newSchema, expressions);

    expect(issues).toHaveLength(1);
    expect(issues[0].type).toBe("removed");
    expect(issues[0].path).toBe("legacyField");
  });

  it("returns no issues when no used paths were removed", () => {
    const oldSchema: VisualSchema = {
      fields: [
        { id: "1", name: "customer", type: "string", required: false },
        { id: "2", name: "unused", type: "string", required: false },
      ],
    };
    const newSchema: VisualSchema = {
      fields: [
        { id: "1", name: "customer", type: "string", required: false },
      ],
    };
    const expressions = new Set(["customer"]);

    const issues = detectRemovedPaths(oldSchema, newSchema, expressions);

    expect(issues).toHaveLength(0);
  });

  it("handles empty expressions", () => {
    const oldSchema: VisualSchema = {
      fields: [{ id: "1", name: "field", type: "string", required: false }],
    };
    const newSchema: VisualSchema = { fields: [] };

    const issues = detectRemovedPaths(oldSchema, newSchema, new Set());

    expect(issues).toHaveLength(0);
  });
});

describe("getExpressionCoverage", () => {
  it("returns 100% coverage when all expressions are valid", () => {
    const schema: VisualSchema = {
      fields: [
        { id: "1", name: "name", type: "string", required: false },
        { id: "2", name: "email", type: "string", required: false },
      ],
    };
    const expressions = new Set(["name", "email"]);

    const coverage = getExpressionCoverage(schema, expressions);

    expect(coverage.total).toBe(2);
    expect(coverage.valid).toBe(2);
    expect(coverage.missing).toBe(0);
    expect(coverage.coverage).toBe(100);
  });

  it("returns 0% coverage when no expressions match", () => {
    const schema: VisualSchema = { fields: [] };
    const expressions = new Set(["unknown", "missing"]);

    const coverage = getExpressionCoverage(schema, expressions);

    expect(coverage.total).toBe(2);
    expect(coverage.valid).toBe(0);
    expect(coverage.missing).toBe(2);
    expect(coverage.coverage).toBe(0);
  });

  it("returns partial coverage", () => {
    const schema: VisualSchema = {
      fields: [{ id: "1", name: "valid", type: "string", required: false }],
    };
    const expressions = new Set(["valid", "invalid"]);

    const coverage = getExpressionCoverage(schema, expressions);

    expect(coverage.total).toBe(2);
    expect(coverage.valid).toBe(1);
    expect(coverage.missing).toBe(1);
    expect(coverage.coverage).toBe(50);
  });

  it("returns 100% for empty expressions", () => {
    const schema: VisualSchema = {
      fields: [{ id: "1", name: "field", type: "string", required: false }],
    };

    const coverage = getExpressionCoverage(schema, new Set());

    expect(coverage.total).toBe(0);
    expect(coverage.coverage).toBe(100);
  });
});
