import type { CSSStyles, DocumentStyles } from "../types.js";

/**
 * CSS properties that cascade from document styles to block styles.
 *
 * Block-level values always take precedence when present.
 */
export const INHERITABLE_STYLE_KEYS = [
  "fontFamily",
  "fontSize",
  "fontWeight",
  "color",
  "lineHeight",
  "letterSpacing",
  "textAlign",
  "backgroundColor",
] as const satisfies readonly (keyof CSSStyles)[];

/**
 * Returns a defensive copy of document styles.
 */
export function resolveDocumentStyles(
  documentStyles?: DocumentStyles | CSSStyles,
): CSSStyles {
  return documentStyles ? { ...(documentStyles as CSSStyles) } : {};
}

/**
 * Resolves effective block styles using the document->block cascade:
 * - inheritable document properties are used as fallbacks
 * - block styles override document styles when defined
 */
export function resolveBlockStyles(
  documentStyles?: DocumentStyles | CSSStyles,
  blockStyles?: CSSStyles,
): CSSStyles {
  if (!documentStyles && !blockStyles) return {};
  if (!documentStyles) return blockStyles ? { ...blockStyles } : {};

  const resolved: CSSStyles = blockStyles ? { ...blockStyles } : {};
  const documentAsCss = documentStyles as CSSStyles;

  for (const key of INHERITABLE_STYLE_KEYS) {
    if (resolved[key] === undefined && documentAsCss[key] !== undefined) {
      (resolved as Record<string, string | number | undefined>)[key] =
        documentAsCss[key];
    }
  }

  return resolved;
}

/**
 * Resolves effective block styles using hierarchical cascade:
 * document -> ancestor styles (root to parent) -> block styles.
 */
export function resolveBlockStylesWithAncestors(
  documentStyles?: DocumentStyles | CSSStyles,
  ancestorStyles: CSSStyles[] = [],
  blockStyles?: CSSStyles,
): CSSStyles {
  let resolved = resolveBlockStyles(documentStyles, undefined);

  for (const ancestorStyle of ancestorStyles) {
    resolved = resolveBlockStyles(resolved, ancestorStyle);
  }

  return resolveBlockStyles(resolved, blockStyles);
}
