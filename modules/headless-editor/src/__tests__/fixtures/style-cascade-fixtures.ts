import type { CSSStyles } from "../../types";

export interface StyleCascadeFixture {
  id: string;
  description: string;
  documentStyles?: CSSStyles;
  ancestorStyles: CSSStyles[];
  blockStyles?: CSSStyles;
  expected: CSSStyles;
}

export const STYLE_CASCADE_FIXTURES: StyleCascadeFixture[] = [
  {
    id: "doc_container_child_inherit_font_size",
    description:
      "Child block inherits font size from nearest ancestor when child does not define it.",
    documentStyles: {
      fontSize: "4rem",
      color: "#333333",
    },
    ancestorStyles: [
      {
        fontSize: "2rem",
      },
    ],
    expected: {
      fontSize: "2rem",
      color: "#333333",
    },
  },
  {
    id: "child_override_wins",
    description:
      "Child inline style overrides inherited value from document and ancestor chain.",
    documentStyles: {
      fontSize: "4rem",
      color: "#333333",
    },
    ancestorStyles: [
      {
        fontSize: "2rem",
      },
    ],
    blockStyles: {
      fontSize: "1rem",
    },
    expected: {
      fontSize: "1rem",
      color: "#333333",
    },
  },
  {
    id: "deep_ancestor_chain",
    description:
      "Nearest ancestor value wins in deep hierarchy when block has no override.",
    documentStyles: {
      color: "#333333",
    },
    ancestorStyles: [
      {
        color: "#222222",
      },
      {
        color: "#111111",
      },
    ],
    expected: {
      color: "#111111",
    },
  },
  {
    id: "non_inheritable_not_propagated",
    description:
      "Non-inheritable keys (layout/spacing) do not flow from parent to child.",
    documentStyles: {
      fontSize: "14px",
    },
    ancestorStyles: [
      {
        paddingTop: "12px",
        marginBottom: "8px",
      },
    ],
    expected: {
      fontSize: "14px",
    },
  },
  {
    id: "background_color_inherited",
    description:
      "Background color inherits through ancestors when child is unset.",
    documentStyles: {
      backgroundColor: "#ffffff",
    },
    ancestorStyles: [
      {
        backgroundColor: "#ffeecc",
      },
    ],
    expected: {
      backgroundColor: "#ffeecc",
    },
  },
];
