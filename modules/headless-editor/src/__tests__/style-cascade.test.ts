import { describe, expect, it } from "vitest";
import {
  INHERITABLE_STYLE_KEYS,
  resolveBlockStyles,
  resolveBlockStylesWithAncestors,
  resolveDocumentStyles,
} from "../styles/cascade";
import { STYLE_CASCADE_FIXTURES } from "./fixtures/style-cascade-fixtures";

describe("style cascade", () => {
  it("defines the expected inheritable properties", () => {
    expect(INHERITABLE_STYLE_KEYS).toEqual([
      "fontFamily",
      "fontSize",
      "fontWeight",
      "color",
      "lineHeight",
      "letterSpacing",
      "textAlign",
      "backgroundColor",
    ]);
  });

  it("returns empty object when no styles are provided", () => {
    expect(resolveBlockStyles()).toEqual({});
    expect(resolveDocumentStyles()).toEqual({});
  });

  it("returns a copy of document styles", () => {
    const documentStyles = {
      fontFamily: "Georgia, serif",
      color: "#333333",
    };

    const resolved = resolveDocumentStyles(documentStyles);

    expect(resolved).toEqual(documentStyles);
    expect(resolved).not.toBe(documentStyles);
  });

  it("inherits only allowed properties from document styles", () => {
    const resolved = resolveBlockStyles(
      {
        fontFamily: "Georgia, serif",
        color: "#222222",
        backgroundColor: "#f5f5f5",
        marginTop: "20px",
      },
      undefined,
    );

    expect(resolved).toEqual({
      fontFamily: "Georgia, serif",
      color: "#222222",
      backgroundColor: "#f5f5f5",
    });
  });

  it("lets block styles override document styles", () => {
    const resolved = resolveBlockStyles(
      {
        fontSize: "14px",
        color: "#444444",
      },
      {
        color: "#111111",
      },
    );

    expect(resolved).toEqual({
      fontSize: "14px",
      color: "#111111",
    });
  });

  it("preserves non-inheritable block-only styles", () => {
    const resolved = resolveBlockStyles(
      {
        fontSize: "14px",
        backgroundColor: "#eeeeee",
      },
      {
        backgroundColor: "#ffffff",
        paddingTop: "8px",
      },
    );

    expect(resolved).toEqual({
      fontSize: "14px",
      backgroundColor: "#ffffff",
      paddingTop: "8px",
    });
  });

  it("cascades styles through ancestors with nearest override wins", () => {
    const resolved = resolveBlockStylesWithAncestors(
      {
        fontSize: "14px",
        color: "#333333",
      },
      [
        { fontSize: "2rem", backgroundColor: "#f3f3f3" },
        { color: "#111111" },
      ],
      undefined,
    );

    expect(resolved).toEqual({
      fontSize: "2rem",
      color: "#111111",
      backgroundColor: "#f3f3f3",
    });
  });

  it("allows block styles to override inherited ancestor values", () => {
    const resolved = resolveBlockStylesWithAncestors(
      {
        fontSize: "14px",
        color: "#333333",
      },
      [{ fontSize: "2rem", color: "#111111" }],
      { color: "#ff0000", paddingTop: "8px" },
    );

    expect(resolved).toEqual({
      fontSize: "2rem",
      color: "#ff0000",
      paddingTop: "8px",
    });
  });

  describe("fixture-driven cascade contract", () => {
    for (const fixture of STYLE_CASCADE_FIXTURES) {
      it(`${fixture.id}: ${fixture.description}`, () => {
        const resolved = resolveBlockStylesWithAncestors(
          fixture.documentStyles,
          fixture.ancestorStyles,
          fixture.blockStyles,
        );

        expect(resolved).toEqual(fixture.expected);
      });
    }
  });
});
