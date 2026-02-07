/**
 * Rich text content structure - compatible with TipTap/ProseMirror JSONContent.
 * This is a framework-agnostic type that matches the TipTap output format.
 */
export interface RichTextContent {
  type: string;
  attrs?: Record<string, unknown>;
  content?: RichTextContent[];
  marks?: RichTextMark[];
  text?: string;
}

/**
 * Mark applied to text (bold, italic, link, etc.).
 */
export interface RichTextMark {
  type: string;
  attrs?: Record<string, unknown>;
}

/**
 * Create an empty rich text document.
 */
export function createEmptyDocument(): RichTextContent {
  return {
    type: "doc",
    content: [
      {
        type: "paragraph",
      },
    ],
  };
}

/**
 * Create a rich text document with plain text content.
 */
export function createTextDocument(text: string): RichTextContent {
  return {
    type: "doc",
    content: [
      {
        type: "paragraph",
        content: text
          ? [
              {
                type: "text",
                text,
              },
            ]
          : undefined,
      },
    ],
  };
}

/**
 * Extract plain text from rich text content.
 */
export function extractPlainText(content: RichTextContent): string {
  if (content.text) {
    return content.text;
  }

  if (content.content) {
    return content.content.map(extractPlainText).join("");
  }

  return "";
}
