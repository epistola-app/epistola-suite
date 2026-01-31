import {describe, expect, it} from "vitest";
import {
    ARRAY_METHODS,
    BOOLEAN_METHODS,
    formatTypeForDisplay,
    getMethodName,
    getMethodsForType,
    inferMethodReturnType,
    type InferredType,
    inferType,
    isMethodCall,
    NUMBER_METHODS,
    parsePath,
    resolvePathType,
    resolvePathValue,
    STRING_METHODS,
} from "./type-inference";
import type {ScopeVariable} from "../context/ScopeContext";

describe("inferType", () => {
  describe("primitives", () => {
    it("infers string type", () => {
      expect(inferType("hello")).toEqual({ kind: "primitive", type: "string" });
      expect(inferType("")).toEqual({ kind: "primitive", type: "string" });
    });

    it("infers number type", () => {
      expect(inferType(42)).toEqual({ kind: "primitive", type: "number" });
      expect(inferType(0)).toEqual({ kind: "primitive", type: "number" });
      expect(inferType(-1.5)).toEqual({ kind: "primitive", type: "number" });
      expect(inferType(NaN)).toEqual({ kind: "primitive", type: "number" });
      expect(inferType(Infinity)).toEqual({ kind: "primitive", type: "number" });
    });

    it("infers boolean type", () => {
      expect(inferType(true)).toEqual({ kind: "primitive", type: "boolean" });
      expect(inferType(false)).toEqual({ kind: "primitive", type: "boolean" });
    });

    it("infers null type", () => {
      expect(inferType(null)).toEqual({ kind: "primitive", type: "null" });
    });

    it("infers undefined type", () => {
      expect(inferType(undefined)).toEqual({ kind: "primitive", type: "undefined" });
    });
  });

  describe("arrays", () => {
    it("infers array with string elements", () => {
      expect(inferType(["a", "b", "c"])).toEqual({
        kind: "array",
        elementType: { kind: "primitive", type: "string" },
      });
    });

    it("infers array with number elements", () => {
      expect(inferType([1, 2, 3])).toEqual({
        kind: "array",
        elementType: { kind: "primitive", type: "number" },
      });
    });

    it("infers empty array as unknown element type", () => {
      expect(inferType([])).toEqual({
        kind: "array",
        elementType: { kind: "unknown" },
      });
    });

    it("infers nested arrays", () => {
      expect(
        inferType([
          [1, 2],
          [3, 4],
        ]),
      ).toEqual({
        kind: "array",
        elementType: {
          kind: "array",
          elementType: { kind: "primitive", type: "number" },
        },
      });
    });

    it("infers array of objects", () => {
      const result = inferType([{ name: "John" }]);
      expect(result.kind).toBe("array");
      if (result.kind === "array") {
        expect(result.elementType.kind).toBe("object");
      }
    });
  });

  describe("objects", () => {
    it("infers simple object properties", () => {
      const result = inferType({ name: "John", age: 30 });
      expect(result).toEqual({
        kind: "object",
        properties: {
          name: { kind: "primitive", type: "string" },
          age: { kind: "primitive", type: "number" },
        },
      });
    });

    it("infers nested objects", () => {
      const result = inferType({ customer: { name: "John" } });
      expect(result).toEqual({
        kind: "object",
        properties: {
          customer: {
            kind: "object",
            properties: {
              name: { kind: "primitive", type: "string" },
            },
          },
        },
      });
    });

    it("infers empty object", () => {
      expect(inferType({})).toEqual({ kind: "object", properties: {} });
    });
  });
});

describe("getMethodsForType", () => {
  it("returns STRING_METHODS for string type", () => {
    const type: InferredType = { kind: "primitive", type: "string" };
    expect(getMethodsForType(type)).toBe(STRING_METHODS);
  });

  it("returns NUMBER_METHODS for number type", () => {
    const type: InferredType = { kind: "primitive", type: "number" };
    expect(getMethodsForType(type)).toBe(NUMBER_METHODS);
  });

  it("returns BOOLEAN_METHODS for boolean type", () => {
    const type: InferredType = { kind: "primitive", type: "boolean" };
    expect(getMethodsForType(type)).toBe(BOOLEAN_METHODS);
  });

  it("returns ARRAY_METHODS for array type", () => {
    const type: InferredType = { kind: "array", elementType: { kind: "unknown" } };
    expect(getMethodsForType(type)).toBe(ARRAY_METHODS);
  });

  it("returns empty array for null type", () => {
    const type: InferredType = { kind: "primitive", type: "null" };
    expect(getMethodsForType(type)).toEqual([]);
  });

  it("returns empty array for object type", () => {
    const type: InferredType = { kind: "object", properties: {} };
    expect(getMethodsForType(type)).toEqual([]);
  });

  it("returns empty array for unknown type", () => {
    const type: InferredType = { kind: "unknown" };
    expect(getMethodsForType(type)).toEqual([]);
  });
});

describe("parsePath", () => {
  it("parses simple property path", () => {
    expect(parsePath("customer")).toEqual(["customer"]);
    expect(parsePath("customer.name")).toEqual(["customer", "name"]);
  });

  it("parses nested property path", () => {
    expect(parsePath("a.b.c.d")).toEqual(["a", "b", "c", "d"]);
  });

  it("parses array index access", () => {
    expect(parsePath("orders[0]")).toEqual(["orders", "[0]"]);
    expect(parsePath("orders[0].name")).toEqual(["orders", "[0]", "name"]);
  });

  it("parses multiple array indices", () => {
    expect(parsePath("matrix[0][1]")).toEqual(["matrix", "[0]", "[1]"]);
  });

  it("parses method calls", () => {
    expect(parsePath("name.toLowerCase()")).toEqual(["name", "toLowerCase()"]);
    expect(parsePath("name.trim().toLowerCase()")).toEqual(["name", "trim()", "toLowerCase()"]);
  });

  it("parses method calls with arguments", () => {
    expect(parsePath("name.slice(0, 5)")).toEqual(["name", "slice(0, 5)"]);
    expect(parsePath('text.split(",")').length).toBe(2);
  });

  it("parses mixed paths", () => {
    expect(parsePath("orders[0].customer.name.toUpperCase()")).toEqual([
      "orders",
      "[0]",
      "customer",
      "name",
      "toUpperCase()",
    ]);
  });

  it("handles empty string", () => {
    expect(parsePath("")).toEqual([]);
  });

  it("handles leading dot", () => {
    expect(parsePath(".name")).toEqual(["name"]);
  });
});

describe("isMethodCall", () => {
  it("returns true for method calls", () => {
    expect(isMethodCall("toLowerCase()")).toBe(true);
    expect(isMethodCall("slice(0, 5)")).toBe(true);
    expect(isMethodCall("split(',')")).toBe(true);
  });

  it("returns false for property access", () => {
    expect(isMethodCall("name")).toBe(false);
    expect(isMethodCall("length")).toBe(false);
    expect(isMethodCall("[0]")).toBe(false);
  });

  it("returns false for partial parentheses", () => {
    expect(isMethodCall("method(")).toBe(false);
    expect(isMethodCall(")")).toBe(false);
  });
});

describe("getMethodName", () => {
  it("extracts method name from call", () => {
    expect(getMethodName("toLowerCase()")).toBe("toLowerCase");
    expect(getMethodName("slice(0, 5)")).toBe("slice");
    expect(getMethodName("split(',')")).toBe("split");
  });

  it("returns segment as-is if no parentheses", () => {
    expect(getMethodName("name")).toBe("name");
    expect(getMethodName("length")).toBe("length");
  });
});

describe("inferMethodReturnType", () => {
  describe("string methods", () => {
    const stringType: InferredType = { kind: "primitive", type: "string" };

    it("returns string for string-returning methods", () => {
      const stringMethods = ["toUpperCase", "toLowerCase", "trim", "slice", "replace"];
      for (const method of stringMethods) {
        expect(inferMethodReturnType(method, stringType)).toEqual({
          kind: "primitive",
          type: "string",
        });
      }
    });

    it("returns number for number-returning methods", () => {
      const numberMethods = ["indexOf", "lastIndexOf", "charCodeAt", "search"];
      for (const method of numberMethods) {
        expect(inferMethodReturnType(method, stringType)).toEqual({
          kind: "primitive",
          type: "number",
        });
      }
    });

    it("returns boolean for boolean-returning methods", () => {
      const boolMethods = ["includes", "startsWith", "endsWith"];
      for (const method of boolMethods) {
        expect(inferMethodReturnType(method, stringType)).toEqual({
          kind: "primitive",
          type: "boolean",
        });
      }
    });

    it("returns string array for split", () => {
      expect(inferMethodReturnType("split", stringType)).toEqual({
        kind: "array",
        elementType: { kind: "primitive", type: "string" },
      });
    });

    it("returns unknown for unknown method", () => {
      expect(inferMethodReturnType("unknownMethod", stringType)).toEqual({ kind: "unknown" });
    });
  });

  describe("array methods", () => {
    const arrayType: InferredType = {
      kind: "array",
      elementType: { kind: "primitive", type: "string" },
    };

    it("returns same array type for filter/slice", () => {
      expect(inferMethodReturnType("filter", arrayType)).toEqual(arrayType);
      expect(inferMethodReturnType("slice", arrayType)).toEqual(arrayType);
    });

    it("returns element type for find", () => {
      expect(inferMethodReturnType("find", arrayType)).toEqual({
        kind: "primitive",
        type: "string",
      });
    });

    it("returns number for indexOf/findIndex", () => {
      expect(inferMethodReturnType("indexOf", arrayType)).toEqual({
        kind: "primitive",
        type: "number",
      });
      expect(inferMethodReturnType("findIndex", arrayType)).toEqual({
        kind: "primitive",
        type: "number",
      });
    });

    it("returns boolean for includes/some/every", () => {
      expect(inferMethodReturnType("includes", arrayType)).toEqual({
        kind: "primitive",
        type: "boolean",
      });
      expect(inferMethodReturnType("some", arrayType)).toEqual({
        kind: "primitive",
        type: "boolean",
      });
    });

    it("returns string for join", () => {
      expect(inferMethodReturnType("join", arrayType)).toEqual({
        kind: "primitive",
        type: "string",
      });
    });

    it("returns unknown for map (callback return type unknown)", () => {
      const result = inferMethodReturnType("map", arrayType);
      expect(result.kind).toBe("array");
      if (result.kind === "array") {
        expect(result.elementType).toEqual({ kind: "unknown" });
      }
    });
  });

  describe("number methods", () => {
    const numberType: InferredType = { kind: "primitive", type: "number" };

    it("returns string for formatting methods", () => {
      const methods = ["toFixed", "toPrecision", "toString", "toLocaleString"];
      for (const method of methods) {
        expect(inferMethodReturnType(method, numberType)).toEqual({
          kind: "primitive",
          type: "string",
        });
      }
    });
  });

  describe("boolean methods", () => {
    const boolType: InferredType = { kind: "primitive", type: "boolean" };

    it("returns string for toString", () => {
      expect(inferMethodReturnType("toString", boolType)).toEqual({
        kind: "primitive",
        type: "string",
      });
    });
  });
});

describe("resolvePathValue", () => {
  const testData = {
    customer: {
      name: "John",
      age: 30,
      orders: [
        { id: 1, items: ["apple", "banana"] },
        { id: 2, items: ["orange"] },
      ],
    },
    tags: ["vip", "premium"],
  };

  it("resolves simple property", () => {
    expect(resolvePathValue(["customer"], testData, [])).toEqual(testData.customer);
    expect(resolvePathValue(["tags"], testData, [])).toEqual(testData.tags);
  });

  it("resolves nested property", () => {
    expect(resolvePathValue(["customer", "name"], testData, [])).toBe("John");
    expect(resolvePathValue(["customer", "age"], testData, [])).toBe(30);
  });

  it("resolves array index", () => {
    expect(resolvePathValue(["tags", "[0]"], testData, [])).toBe("vip");
    expect(resolvePathValue(["customer", "orders", "[0]"], testData, [])).toEqual(
      testData.customer.orders[0],
    );
  });

  it("resolves deep nested path", () => {
    expect(resolvePathValue(["customer", "orders", "[0]", "items", "[1]"], testData, [])).toBe(
      "banana",
    );
  });

  it("returns undefined for non-existent path", () => {
    expect(resolvePathValue(["nonexistent"], testData, [])).toBeUndefined();
    expect(resolvePathValue(["customer", "nonexistent"], testData, [])).toBeUndefined();
  });

  it("returns undefined for out-of-bounds array access", () => {
    expect(resolvePathValue(["tags", "[99]"], testData, [])).toBeUndefined();
  });

  it("handles scope variables for loop items", () => {
    const scopeVars: ScopeVariable[] = [
      { name: "order", type: "loop-item", arrayPath: "customer.orders" },
    ];
    expect(resolvePathValue(["order"], testData, scopeVars)).toEqual(testData.customer.orders[0]);
    expect(resolvePathValue(["order", "id"], testData, scopeVars)).toBe(1);
  });

  it("handles scope variables for loop index", () => {
    const scopeVars: ScopeVariable[] = [
      { name: "idx", type: "loop-index", arrayPath: "customer.orders" },
    ];
    expect(resolvePathValue(["idx"], testData, scopeVars)).toBe(0);
  });
});

describe("resolvePathType", () => {
  const testData = {
    customer: {
      name: "John",
      orders: [{ id: 1 }],
    },
    count: 42,
  };

  it("resolves type for simple property", () => {
    expect(resolvePathType(["customer", "name"], testData, [])).toEqual({
      kind: "primitive",
      type: "string",
    });
    expect(resolvePathType(["count"], testData, [])).toEqual({
      kind: "primitive",
      type: "number",
    });
  });

  it("resolves type through array access", () => {
    expect(resolvePathType(["customer", "orders", "[0]", "id"], testData, [])).toEqual({
      kind: "primitive",
      type: "number",
    });
  });

  it("resolves type through method calls", () => {
    expect(resolvePathType(["customer", "name", "toLowerCase()"], testData, [])).toEqual({
      kind: "primitive",
      type: "string",
    });
  });

  it("chains method return types", () => {
    // name.toLowerCase().split("") should be string[]
    expect(resolvePathType(["customer", "name", "toLowerCase()", "split()"], testData, [])).toEqual(
      {
        kind: "array",
        elementType: { kind: "primitive", type: "string" },
      },
    );
  });

  it("returns unknown for non-existent path", () => {
    expect(resolvePathType(["nonexistent"], testData, [])).toEqual({ kind: "unknown" });
  });

  it("returns object type for empty path", () => {
    expect(resolvePathType([], testData, [])).toEqual({ kind: "object", properties: {} });
  });
});

describe("formatTypeForDisplay", () => {
  it("formats primitive types", () => {
    expect(formatTypeForDisplay({ kind: "primitive", type: "string" })).toBe("string");
    expect(formatTypeForDisplay({ kind: "primitive", type: "number" })).toBe("number");
    expect(formatTypeForDisplay({ kind: "primitive", type: "boolean" })).toBe("boolean");
    expect(formatTypeForDisplay({ kind: "primitive", type: "null" })).toBe("null");
    expect(formatTypeForDisplay({ kind: "primitive", type: "undefined" })).toBe("undefined");
  });

  it("formats array types", () => {
    expect(
      formatTypeForDisplay({
        kind: "array",
        elementType: { kind: "primitive", type: "string" },
      }),
    ).toBe("string[]");
  });

  it("formats nested array types", () => {
    expect(
      formatTypeForDisplay({
        kind: "array",
        elementType: {
          kind: "array",
          elementType: { kind: "primitive", type: "number" },
        },
      }),
    ).toBe("number[][]");
  });

  it("formats object type", () => {
    expect(formatTypeForDisplay({ kind: "object", properties: {} })).toBe("object");
  });

  it("formats unknown type", () => {
    expect(formatTypeForDisplay({ kind: "unknown" })).toBe("unknown");
  });
});
