/**
 * CSS properties object - framework-agnostic replacement for React.CSSProperties.
 * Uses camelCase property names that can be converted to CSS.
 */
export interface CSSStyles {
  // Typography
  fontFamily?: string;
  fontSize?: string;
  fontWeight?: string | number;
  fontStyle?: string;
  color?: string;
  lineHeight?: string | number;
  letterSpacing?: string;
  textAlign?: "left" | "center" | "right" | "justify";
  textDecoration?: string;
  textTransform?: string;

  // Spacing
  margin?: string;
  marginTop?: string;
  marginRight?: string;
  marginBottom?: string;
  marginLeft?: string;
  padding?: string;
  paddingTop?: string;
  paddingRight?: string;
  paddingBottom?: string;
  paddingLeft?: string;

  // Box model
  width?: string;
  height?: string;
  minWidth?: string;
  maxWidth?: string;
  minHeight?: string;
  maxHeight?: string;

  // Background
  backgroundColor?: string;
  backgroundImage?: string;
  backgroundSize?: string;
  backgroundPosition?: string;
  backgroundRepeat?: string;

  // Border
  border?: string;
  borderTop?: string;
  borderRight?: string;
  borderBottom?: string;
  borderLeft?: string;
  borderWidth?: string;
  borderStyle?: string;
  borderColor?: string;
  borderRadius?: string;

  // Flexbox
  display?: string;
  flexDirection?: string;
  flexWrap?: string;
  justifyContent?: string;
  alignItems?: string;
  alignContent?: string;
  gap?: string;
  flex?: string | number;
  flexGrow?: number;
  flexShrink?: number;
  flexBasis?: string;

  // Grid
  gridTemplateColumns?: string;
  gridTemplateRows?: string;
  gridColumn?: string;
  gridRow?: string;

  // Position
  position?: string;
  top?: string;
  right?: string;
  bottom?: string;
  left?: string;
  zIndex?: number;

  // Other
  overflow?: string;
  overflowX?: string;
  overflowY?: string;
  opacity?: number;
  visibility?: string;
  cursor?: string;
  boxShadow?: string;
  whiteSpace?: string;
  wordBreak?: string;
  verticalAlign?: string;
}

/**
 * Convert camelCase style object to CSS string.
 */
export function stylesToString(styles: CSSStyles): string {
  if (!styles || Object.keys(styles).length === 0) {
    return "";
  }

  return Object.entries(styles)
    .filter(([, value]) => value !== undefined && value !== null && value !== "")
    .map(([key, value]) => {
      // Convert camelCase to kebab-case
      const cssProperty = key.replace(/([A-Z])/g, "-$1").toLowerCase();
      return `${cssProperty}: ${value}`;
    })
    .join("; ");
}

/**
 * Merge multiple style objects, later ones override earlier ones.
 */
export function mergeStyles(...styles: (CSSStyles | undefined)[]): CSSStyles {
  return styles.reduce<CSSStyles>((acc, style) => {
    if (!style) return acc;
    return { ...acc, ...style };
  }, {});
}

/**
 * Properties that are inherited in CSS (cascade down to children).
 */
export const INHERITED_PROPERTIES: (keyof CSSStyles)[] = [
  "fontFamily",
  "fontSize",
  "fontWeight",
  "fontStyle",
  "color",
  "lineHeight",
  "letterSpacing",
  "textAlign",
];

/**
 * Extract only inherited properties from a style object.
 */
export function getInheritedStyles(styles: CSSStyles): CSSStyles {
  const inherited: CSSStyles = {};
  for (const prop of INHERITED_PROPERTIES) {
    if (styles[prop] !== undefined) {
      (inherited as Record<string, unknown>)[prop] = styles[prop];
    }
  }
  return inherited;
}
