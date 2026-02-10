import { describe, expect, it } from "vitest";
import {
  INHERITABLE_STYLE_KEYS,
  resolveBlockStyles,
  resolveDocumentStyles,
} from "../styles/cascade";

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
});
