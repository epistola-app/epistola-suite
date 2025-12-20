# Styling System

The template editor includes a comprehensive styling system that allows users to customize the appearance of both the document and individual blocks.

## Overview

The styling system consists of:

1. **Document Styles** - Global typography and background settings that cascade to all blocks
2. **Block Styles** - Per-block styling that overrides document defaults
3. **Page Settings** - Document margins and page configuration

## Inspector Panel

The Inspector panel is located on the left side of the editor and contains two tabs:

### Properties Tab

Shows contextual information and settings:

**When no block is selected (Document level):**
- Template name
- Page format and orientation
- Editable margin controls (top, right, bottom, left in mm)
- Block count

**When a block is selected:**
- Block type
- Block ID
- Contextual tips specific to the block type

### Styles Tab

Provides visual controls for styling properties organized into categories.

## Style Categories

### 1. Spacing

Controls the space around and inside elements.

| Property | Description | Units |
|----------|-------------|-------|
| Padding (top, right, bottom, left) | Inner spacing | px, em, rem, % |
| Margin (top, right, bottom, left) | Outer spacing | px, em, rem, % |

Features:
- Visual cross-pattern input for intuitive editing
- Link toggle to apply same value to all sides
- Per-side unit selection

### 2. Typography

Controls text appearance.

| Property | Description | Options/Units |
|----------|-------------|---------------|
| Font Family | Typeface | System Default, Arial, Georgia, Times New Roman, Courier New, Verdana |
| Font Size | Text size | px, em, rem |
| Font Weight | Text thickness | Normal (400), Medium (500), Semi Bold (600), Bold (700) |
| Color | Text color | Hex color picker |
| Text Align | Horizontal alignment | Left, Center, Right, Justify |
| Line Height | Vertical spacing between lines | px, em, % |
| Letter Spacing | Space between characters | px, em |

### 3. Background

| Property | Description | Options |
|----------|-------------|---------|
| Background Color | Fill color | Hex color picker |

### 4. Borders

| Property | Description | Options/Units |
|----------|-------------|---------------|
| Border Width | Line thickness | px |
| Border Style | Line pattern | None, Solid, Dashed, Dotted, Double |
| Border Color | Line color | Hex color picker |
| Border Radius | Corner rounding | px, % |

### 5. Effects

| Property | Description | Options |
|----------|-------------|---------|
| Box Shadow | Drop shadow | X offset, Y offset, blur, spread, color |
| Opacity | Transparency | 0 to 1 slider |

Box shadow includes:
- Toggle to enable/disable
- Live preview
- Individual controls for each shadow parameter

### 6. Layout

| Property | Description | Options/Units |
|----------|-------------|---------------|
| Width | Element width | px, %, em |
| Height | Element height | px, %, em |
| Display | Layout mode | Block, Flex, None |
| Flex Direction* | Main axis direction | Row, Row Reverse, Column, Column Reverse |
| Gap* | Space between flex items | px, em, rem |
| Align Items* | Cross-axis alignment | Start, Center, End, Stretch, Baseline |
| Justify Content* | Main-axis alignment | Start, Center, End, Space Between, Space Around, Space Evenly |

*Only shown when Display is set to "Flex"

## Style Inheritance

The styling system uses CSS inheritance principles:

### Inheritable Properties

These properties cascade from document to blocks:
- `fontFamily`
- `fontSize`
- `fontWeight`
- `color`
- `lineHeight`
- `letterSpacing`
- `textAlign`

### Non-Inheritable Properties

These properties only apply to the element they're set on:
- Spacing (padding, margin)
- Background
- Borders
- Effects (box shadow, opacity)
- Layout (width, height, display, flex properties)

### Inheritance Flow

```
Document Styles
    ↓ (typography properties cascade)
Block Styles (override document defaults)
    ↓
Rendered Output
```

## Implementation Details

### Data Model

**Document Styles** (`DocumentStyles` in `types/template.ts`):
```typescript
interface DocumentStyles {
  fontFamily?: string;
  fontSize?: string;
  fontWeight?: string;
  color?: string;
  lineHeight?: string;
  letterSpacing?: string;
  textAlign?: 'left' | 'center' | 'right' | 'justify';
  backgroundColor?: string;
}
```

**Block Styles** (stored as `CSSProperties` in each block's `styles` field):
```typescript
interface BaseBlock {
  id: string;
  type: string;
  styles?: CSSProperties;
}
```

### Key Files

| File | Purpose |
|------|---------|
| `src/types/styles.ts` | Style constants, options, and helper functions |
| `src/types/template.ts` | `DocumentStyles` interface |
| `src/store/editorStore.ts` | `updateDocumentStyles` and `updatePageSettings` actions |
| `src/components/styling/StyleSidebar.tsx` | Main inspector panel with tabs |
| `src/components/styling/DocumentStyleEditor.tsx` | Document-level style controls |
| `src/components/styling/BlockStyleEditor.tsx` | Block-level style controls |
| `src/components/styling/StyleSection.tsx` | Collapsible accordion sections |
| `src/components/styling/inputs/*.tsx` | Reusable input components |

### Input Components

| Component | Purpose |
|-----------|---------|
| `NumberInput` | Numeric values with unit selector |
| `ColorPicker` | Color selection with hex input and swatch |
| `SelectInput` | Dropdown for predefined options |
| `SpacingInput` | 4-sided spacing editor (padding/margin) |
| `ToggleGroup` | Button group for exclusive options (text align) |
| `SliderInput` | Range slider (opacity) |
| `BorderInput` | Combined border controls |
| `BoxShadowInput` | Shadow editor with preview |

## Editor Integration

Styles are applied in two places:

### 1. Canvas (Editor View)

- Document styles applied to the content wrapper in `Canvas.tsx`
- Block styles applied to each block component
- UI controls (toolbars, tips) are isolated from style inheritance

### 2. Preview

- Document styles applied to the A4 container
- Block styles merged with document styles using `mergeStyles()` helper
- Inheritance is handled during HTML rendering in `Preview.tsx`

## Usage Examples

### Setting Document Typography

1. Click on the canvas background (deselect any block)
2. Go to the Styles tab
3. Expand the Typography section
4. Select a font family (e.g., "Georgia")
5. Set font size (e.g., "14px")
6. Choose a text color

All blocks will inherit these settings unless they have their own overrides.

### Styling a Specific Block

1. Click on a block to select it
2. Go to the Styles tab
3. Set block-specific styles:
   - Add padding for inner spacing
   - Set a background color
   - Add a border
   - Adjust typography to override document defaults

### Creating a Highlighted Section

1. Select a container block
2. In Styles tab:
   - Set Background Color to a light shade (e.g., `#f0f7ff`)
   - Add Padding: 16px on all sides
   - Set Border Radius: 8px
   - Optionally add a subtle border

## Future Enhancements

Planned improvements to the styling system:

- [ ] CSS class input for advanced users
- [ ] Style presets/themes
- [ ] Conditional styling based on data
- [ ] Copy/paste styles between blocks
- [ ] Style templates (save and reuse style combinations)
- [ ] More font options (Google Fonts integration)
- [ ] Gradient backgrounds
- [ ] Multiple shadows
- [ ] CSS variables support
