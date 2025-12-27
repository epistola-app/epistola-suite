import { EditorView } from "@codemirror/view";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { tags } from "@lezer/highlight";

/**
 * CodeMirror theme that uses shadcn CSS variables for consistent styling.
 */
export const expressionEditorTheme = EditorView.theme({
  "&": {
    fontSize: "13px",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
    backgroundColor: "transparent",
  },
  "&.cm-focused": {
    outline: "none",
  },
  ".cm-content": {
    padding: "8px 12px",
    caretColor: "var(--foreground)",
    minHeight: "40px",
  },
  ".cm-line": {
    padding: "0",
  },
  ".cm-cursor": {
    borderLeftColor: "var(--foreground)",
    borderLeftWidth: "2px",
  },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground": {
    backgroundColor: "var(--accent)",
  },
  ".cm-activeLine": {
    backgroundColor: "transparent",
  },
  ".cm-gutters": {
    display: "none",
  },
  ".cm-scroller": {
    overflow: "auto",
  },
  // Autocomplete tooltip styling
  ".cm-tooltip": {
    backgroundColor: "var(--popover)",
    border: "1px solid var(--border)",
    borderRadius: "8px",
    boxShadow: "0 10px 25px -5px rgb(0 0 0 / 0.15), 0 8px 10px -6px rgb(0 0 0 / 0.1)",
    overflow: "hidden",
  },
  ".cm-tooltip-autocomplete.cm-tooltip": {
    backgroundColor: "var(--popover)",
    "& > ul": {
      fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
      fontSize: "13px",
      maxHeight: "280px",
      minWidth: "280px",
      maxWidth: "400px",
      padding: "4px",
      backgroundColor: "var(--popover)",
    },
    "& > ul > li": {
      padding: "4px 6px",
      display: "flex",
      alignItems: "center",
      gap: "5px",
      cursor: "pointer",
      borderRadius: "4px",
      margin: "2px 0",
      transition: "background-color 0.1s ease",
    },
    "& > ul > li:hover": {
      backgroundColor: "var(--muted)",
    },
    "& > ul > li[aria-selected]": {
      backgroundColor: "var(--muted)",
      color: "var(--primary)",
    },
    "& > ul > li[aria-selected] .cm-completionDetail": {
      color: "var(--primary)",
      opacity: "0.8",
    },
    "& > ul > li[aria-selected] .cm-completionIcon": {
      opacity: "1",
    },
  },
  ".cm-completionIcon": {
    fontSize: "14px",
    opacity: "0.6",
    width: "20px",
    textAlign: "center",
    flexShrink: "0",
  },
  ".cm-completionIcon-property::after": {
    content: "'○'",
  },
  ".cm-completionIcon-variable::after": {
    content: "'◆'",
  },
  ".cm-completionIcon-method::after": {
    content: "'ƒ'",
  },
  ".cm-completionLabel": {
    flex: "1",
    fontWeight: "500",
  },
  ".cm-completionDetail": {
    opacity: "0.5",
    fontSize: "11px",
    marginLeft: "auto",
    paddingLeft: "12px",
    fontWeight: "400",
  },
  ".cm-completionInfo": {
    padding: "10px 14px",
    backgroundColor: "var(--popover)",
    borderTop: "1px solid var(--border)",
    fontSize: "12px",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
    color: "var(--muted-foreground)",
  },
  // Scrollbar styling for autocomplete
  ".cm-tooltip-autocomplete ul::-webkit-scrollbar": {
    width: "6px",
  },
  ".cm-tooltip-autocomplete ul::-webkit-scrollbar-track": {
    backgroundColor: "transparent",
  },
  ".cm-tooltip-autocomplete ul::-webkit-scrollbar-thumb": {
    backgroundColor: "var(--border)",
    borderRadius: "3px",
  },
});

/**
 * Syntax highlighting for JavaScript expressions.
 */
export const expressionHighlightStyle = HighlightStyle.define([
  { tag: tags.keyword, color: "var(--chart-4)" },
  { tag: tags.operator, color: "var(--muted-foreground)" },
  { tag: tags.variableName, color: "var(--foreground)" },
  { tag: tags.propertyName, color: "var(--chart-1)" },
  { tag: tags.string, color: "var(--chart-2)" },
  { tag: tags.number, color: "var(--chart-3)" },
  { tag: tags.bool, color: "var(--chart-4)" },
  { tag: tags.null, color: "var(--muted-foreground)" },
  { tag: tags.function(tags.variableName), color: "var(--chart-5)" },
  { tag: tags.comment, color: "var(--muted-foreground)", fontStyle: "italic" },
  { tag: tags.bracket, color: "var(--muted-foreground)" },
  { tag: tags.punctuation, color: "var(--muted-foreground)" },
]);

/**
 * Combined theme extension for expression editing.
 */
export const expressionTheme = [expressionEditorTheme, syntaxHighlighting(expressionHighlightStyle)];
