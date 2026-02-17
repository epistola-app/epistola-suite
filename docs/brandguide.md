# Epistola Visual Styleguide

A comprehensive visual design system for the Epistola application. This guide documents colors, typography, spacing, components, and UI patterns for consistent design across all features.

---

## Color Palette

### Primary - Blue

The primary action color used for interactive elements, selections, and focus states.

| Name | Hex | Tailwind | Usage |
|------|-----|----------|-------|
| Blue 50 | `#eff6ff` | `blue-50` | Active tab background |
| Blue 100 | `#dbeafe` | `blue-100` | Expression chip background |
| Blue 200 | `#bfdbfe` | `blue-200` | Expression chip hover |
| Blue 400 | `#60a5fa` | `blue-400` | Focus borders |
| Blue 500 | `#3b82f6` | `blue-500` | Primary buttons, active toggles |
| Blue 600 | `#2563eb` | `blue-600` | Button hover, active tab text |
| Blue 700 | `#1d4ed8` | `blue-700` | Selected block border |
| Blue 900 | `#1e40af` | `blue-900` | Expression chip text |

### Neutral - Gray

Used for text, borders, backgrounds, and disabled states.

| Name | Hex | Tailwind | Usage |
|------|-----|----------|-------|
| White | `#ffffff` | `white` | Panel backgrounds |
| Gray 50 | `#f9fafb` | `gray-50` | Section headers, disabled bg |
| Gray 100 | `#f3f4f6` | `gray-100` | Secondary button bg, input bg |
| Gray 200 | `#e5e7eb` | `gray-200` | Borders, dividers |
| Gray 300 | `#d1d5db` | `gray-300` | Table borders, dashed borders |
| Gray 400 | `#9ca3af` | `gray-400` | Placeholder text, disabled text |
| Gray 500 | `#6b7280` | `gray-500` | Secondary text, labels |
| Gray 600 | `#4b5563` | `gray-600` | Icon hover |
| Gray 700 | `#374151` | `gray-700` | Primary text |
| Gray 900 | `#1f2937` | `gray-900` | Headers, dark text |

### Additional Neutral Tokens

| Name | Hex | CSS Variable | Usage |
|------|-----|-------------|-------|
| Gray 950 | `#030712` | `--ep-gray-950` | Near-black text, darkest background |
| Black | `#000000` | `--ep-black` | Pure black (overlays, accents) |

### Semantic Colors

#### Success - Green
| Name | Hex | CSS Variable | Usage |
|------|-----|-------------|-------|
| Green 50 | `#f0fdf4` | `--ep-green-50` | Success alert bg |
| Green 100 | `#dcfce7` | `--ep-green-100` | Success badge bg |
| Green 500 | `#22c55e` | `--ep-green-500` | Status indicator |
| Green 700 | `#15803d` | `--ep-green-700` | Success badge text |
| Green 800 | `#166534` | `--ep-green-800` | Success alert text |

#### Warning - Amber (replaces yellow)
| Name | Hex | CSS Variable | Usage |
|------|-----|-------------|-------|
| Amber 50 | `#fffbeb` | `--ep-amber-50` | Warning alert bg, conditional block bg |
| Amber 100 | `#fef3c7` | `--ep-amber-100` | Conditional drop zone, warning badge bg |
| Amber 200 | `#fde68a` | `--ep-amber-200` | Conditional border, warning alert border |
| Amber 500 | `#f59e0b` | `--ep-amber-500` | Active conditional toggle |
| Amber 600 | `#d97706` | `--ep-amber-600` | Conditional text, warning semantic |
| Amber 800 | `#92400e` | `--ep-amber-800` | Warning badge text, warning alert text |

> **Note:** Yellow tokens (`--ep-yellow-*`) are deprecated aliases that map to amber. Use amber directly.

#### Error - Red
| Name | Hex | CSS Variable | Usage |
|------|-----|-------------|-------|
| Red 50 | `#fef2f2` | `--ep-red-50` | Error block bg, error alert bg |
| Red 100 | `#fee2e2` | `--ep-red-100` | Error badge bg |
| Red 200 | `#fecaca` | `--ep-red-200` | Error border, error alert border |
| Red 400 | `#f87171` | `--ep-red-400` | Error input border |
| Red 500 | `#ef4444` | `--ep-red-500` | Delete button, error states |
| Red 600 | `#dc2626` | `--ep-red-600` | Error text, destructive semantic |
| Red 700 | `#b91c1c` | `--ep-red-700` | Error badge text, destructive hover |

#### Info - Purple
| Name | Hex | CSS Variable | Usage |
|------|-----|-------------|-------|
| Purple 50 | `#faf5ff` | `--ep-purple-50` | Loop block bg |
| Purple 100 | `#f3e8ff` | `--ep-purple-100` | Loop drop zone |
| Purple 200 | `#e9d5ff` | `--ep-purple-200` | Loop border |
| Purple 500 | `#a855f7` | `--ep-purple-500` | Loop accent |
| Purple 600 | `#9333ea` | `--ep-purple-600` | Loop text |

### Semantic Aliases

Higher-level design tokens that reference the palette above. Use these in components for consistent theming.

| Alias | CSS Variable | Resolves To | Purpose |
|-------|-------------|-------------|---------|
| Background | `--ep-background` | `gray-50` | Page background |
| Foreground | `--ep-foreground` | `gray-900` | Primary text |
| Card | `--ep-card` | `white` | Card/panel background |
| Card Foreground | `--ep-card-foreground` | `gray-900` | Card text |
| Muted | `--ep-muted` | `gray-100` | Muted background (secondary button bg) |
| Muted Foreground | `--ep-muted-foreground` | `gray-500` | Muted text (labels, placeholders) |
| Accent | `--ep-accent` | `gray-100` | Hover/accent background |
| Accent Foreground | `--ep-accent-foreground` | `gray-900` | Accent text |
| Primary | `--ep-primary` | `blue-600` | Primary action color |
| Primary Foreground | `--ep-primary-foreground` | `white` | Text on primary |
| Destructive | `--ep-destructive` | `red-600` | Destructive action color |
| Destructive Foreground | `--ep-destructive-foreground` | `white` | Text on destructive |
| Success | `--ep-success` | `green-700` | Success color |
| Warning | `--ep-warning` | `amber-600` | Warning color |
| Border Color | `--ep-border-color` | `gray-200` | Default border |
| Input Border | `--ep-input-border` | `gray-300` | Form input border |
| Ring | `--ep-ring` | `blue-500` | Focus ring color |

---

## Typography

### Font Stack

Loaded via [Google Fonts](https://fonts.google.com/specimen/Inter) (weights 400, 500, 600, 700).

```css
font-family: "Inter", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
/* CSS custom property: var(--ep-font-sans) */
```

### Scale

| Name | Size | Tailwind | Usage |
|------|------|----------|-------|
| XS | 12px | `text-xs` | Labels, hints, badges |
| SM | 14px | `text-sm` | Body text, inputs, buttons |
| Base | 16px | `text-base` | Default paragraph |
| LG | 18px | `text-lg` | Modal titles, section headers |

### Weights

| Name | Value | Tailwind | Usage |
|------|-------|----------|-------|
| Normal | 400 | `font-normal` | Body text |
| Medium | 500 | `font-medium` | Section titles, labels |
| Semibold | 600 | `font-semibold` | Important labels |
| Bold | 700 | `font-bold` | Headers, strong emphasis |

### Headings (in rich text)

| Level | Size | Weight | Margin |
|-------|------|--------|--------|
| H1 | 1.75em (28px) | 700 | 0 0 0.5em 0 |
| H2 | 1.4em (22px) | 700 | 0 0 0.5em 0 |
| H3 | 1.15em (18px) | 600 | 0 0 0.5em 0 |

### Monospace

```css
font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
```

Used for: Expression chips, code snippets, variable names.

---

## Spacing

### Scale

| Name | Size | Tailwind | Usage |
|------|------|----------|-------|
| 1 | 4px | `p-1`, `gap-1` | Tight spacing, icon buttons |
| 2 | 8px | `p-2`, `gap-2` | Standard spacing, form gaps |
| 3 | 12px | `p-3`, `gap-3` | Section padding |
| 4 | 16px | `p-4`, `gap-4` | Large spacing, preview padding |
| 6 | 24px | `p-6` | Modal padding |

### Common Patterns

| Element | Padding | Margin |
|---------|---------|--------|
| Small button | `px-2 py-1` | - |
| Regular button | `px-3 py-1.5` | - |
| Panel header | `px-3 py-2` | - |
| Form input | `px-2 py-1` | - |
| Modal content | `p-6` | - |
| Block content | `p-2` to `p-3` | - |
| Paragraphs | - | `0 0 0.5em 0` |

---

## Borders

### Width

| Value | Usage |
|-------|-------|
| 1px | Standard borders, inputs, dividers |
| 2px | Selected block border |

### Radius

| Name | Size | CSS Variable | Usage |
|------|------|-------------|-------|
| SM | 0.375rem (6px) | `--ep-radius-sm` | Small buttons, checkboxes |
| MD | 0.5rem (8px) | `--ep-radius-md` | Inputs, standard buttons, expression chips |
| LG | 0.75rem (12px) | `--ep-radius-lg` | Panels, cards, blocks, modals, alerts |
| XL | 1rem (16px) | `--ep-radius-xl` | Large containers |
| Full | 9999px | `--ep-radius-full` | Badges, status indicators, slider thumb |

### Styles

| Style | Tailwind | Usage |
|-------|----------|-------|
| Solid | `border-solid` | Default |
| Dashed | `border-dashed` | Empty containers, drop zones |

---

## Shadows

Five levels with refined multi-layer values for realistic depth.

| Name | CSS Variable | Value | Usage |
|------|-------------|-------|-------|
| XS | `--ep-shadow-xs` | `0 1px 2px 0 rgb(0 0 0 / 0.03)` | Subtle surface lift |
| SM | `--ep-shadow-sm` | `0 1px 2px 0 rgb(0 0 0 / 0.05)` | Cards, panels |
| MD | `--ep-shadow-md` | `0 1px 3px 0 rgb(0 0 0 / 0.1), 0 1px 2px -1px rgb(0 0 0 / 0.1)` | Elevated cards, preview container |
| LG | `--ep-shadow-lg` | `0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1)` | Dropdowns, popovers |
| XL | `--ep-shadow-xl` | `0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.1)` | Modals, dialogs |

---

## Components

Components are implemented as CSS classes in `modules/design-system/components.css`. Apply via class names rather than composing utility classes.

### Buttons

All buttons use the base `.btn` class combined with a variant class. Buttons include built-in focus ring, disabled state, and transition support.

| Class | Description | Usage |
|-------|-------------|-------|
| `.btn` | Base button (required) | All buttons |
| `.btn-primary` | Blue background, white text | Primary actions (save, create) |
| `.btn-secondary` | Muted gray background | Secondary actions |
| `.btn-outline` | White background, gray border | Tertiary actions |
| `.btn-ghost` | Transparent, no border | Toolbar actions, icon buttons |
| `.btn-destructive` | Red background, white text | Delete, remove actions |
| `.btn-sm` | Smaller size variant | Compact UI areas |
| `.btn-lg` | Larger size variant | Prominent CTAs |
| `.btn-icon` | Square (equal width/height) | Icon-only buttons |

```html
<button class="btn btn-primary">Save</button>
<button class="btn btn-outline btn-sm">Cancel</button>
<button class="btn btn-ghost btn-icon btn-sm">
  <svg class="ep-icon">...</svg>
</button>
```

### Form Inputs

Form controls use the `.ep-*` prefix. All include built-in focus ring (box-shadow approach) and disabled styling.

| Class | Description |
|-------|-------------|
| `.ep-input` | Standard text input |
| `.ep-select` | Native select dropdown |
| `.ep-textarea` | Multi-line text area |
| `.ep-label` | Form label (block display, 500 weight) |
| `.ep-checkbox` | Styled checkbox |

```html
<label class="ep-label">Name</label>
<input class="ep-input" type="text" placeholder="Enter name..." />

<select class="ep-select">
  <option>Option 1</option>
</select>

<textarea class="ep-textarea" placeholder="Description..."></textarea>

<!-- Monospace input (for code/expressions) -->
<input class="ep-input mono" type="text" />
```

### Panels

#### Sidebar Panel
```
bg-white rounded-lg shadow-sm
w-64 flex flex-col overflow-hidden
```

#### Panel Header
```
px-3 py-2 border-b border-gray-200 bg-gray-50
flex items-center justify-between
```

#### Collapsible Section
```
border-b border-gray-200
Header: w-full px-3 py-2 flex items-center justify-between
        text-sm font-medium text-gray-700
        hover:bg-gray-50 transition-colors
Content: px-3 pb-3 space-y-3
```

### Tabs

#### Tab Container
```
flex border-b border-gray-200
```

#### Inactive Tab
```
flex-1 px-3 py-2 text-xs font-medium
text-gray-500 hover:text-gray-700 hover:bg-gray-50
```

#### Active Tab
```
flex-1 px-3 py-2 text-xs font-medium
text-blue-600 border-b-2 border-blue-600 bg-blue-50/50
```

### Modal

#### Overlay
```
fixed inset-0 z-50
flex items-center justify-center
bg-black/25
```

#### Dialog
```
bg-white rounded-lg shadow-xl
max-h-[90vh] flex flex-col
w-full mx-4 max-w-md (or max-w-2xl, max-w-4xl)
```

#### Modal Header
```
flex items-center justify-between
px-6 py-4 border-b border-gray-200
Title: text-lg font-semibold text-gray-900
Close: text-gray-400 hover:text-gray-600
```

#### Modal Content
```
flex-1 overflow-auto p-6
```

### Blocks (Editor)

#### Unselected Block
```
rounded-lg border-2 border-transparent
hover:border-gray-300 transition-all
```

#### Selected Block
```
rounded-lg border-2 border-blue-500 shadow-md
```

#### Container Block (empty)
```
bg-gray-50 border border-dashed border-gray-300 rounded-lg
min-h-[40px] flex items-center justify-center
text-gray-400 text-sm
```

#### Conditional Block
```
bg-amber-50 border border-amber-200 rounded-lg
Header: px-3 py-2 border-b border-amber-200
```

#### Loop Block
```
bg-purple-50 border border-purple-200 rounded-lg
Header: px-3 py-2 border-b border-purple-200
```

#### Loop Block (invalid)
```
bg-red-50 border border-red-200 rounded-lg
```

### Expression Chip

```css
display: inline-flex;
align-items: center;
padding: 2px 8px;
margin: 0 2px;
background-color: #dbeafe;
border: 1px solid #93c5fd;
border-radius: 6px;
font-family: ui-monospace, monospace;
font-size: 0.875em;
color: #1e40af;
cursor: pointer;
transition: background-color 0.15s;

&:hover {
  background-color: #bfdbfe;
}
```

### Status Indicator

#### Ready
```
w-2 h-2 rounded-full bg-green-500
```

#### Loading
```
w-2 h-2 rounded-full bg-yellow-500
```

### Badges

Badges use the `.badge` base class combined with a variant. They render as inline-flex with full border-radius.

| Class | Description |
|-------|-------------|
| `.badge` | Base badge (required) |
| `.badge-primary` | Blue background, blue-800 text |
| `.badge-success` | Green background, green-800 text |
| `.badge-warning` | Amber background, amber-800 text |
| `.badge-destructive` | Red background, red-700 text |
| `.badge-outline` | Transparent, bordered |

```html
<span class="badge badge-primary">Active</span>
<span class="badge badge-success">Published</span>
<span class="badge badge-destructive">Error</span>
```

### Alerts

Inline alert blocks use `.alert` with a variant class.

| Class | Description |
|-------|-------------|
| `.alert` | Base alert (required) |
| `.alert-info` | Blue — informational messages |
| `.alert-success` | Green — success feedback |
| `.alert-warning` | Amber — warning messages |
| `.alert-error` | Red — error messages |

```html
<div class="alert alert-warning">
  <svg class="ep-icon">...</svg>
  <div>
    <div class="alert-title">Warning</div>
    <div>This action cannot be undone.</div>
  </div>
</div>
```

### Cards

Cards use the `.card` class with optional header/content/footer sub-components.

| Class | Description |
|-------|-------------|
| `.card` | Card container (white bg, border, shadow-sm, radius-lg) |
| `.card-header` | Header with bottom border and flex-column layout |
| `.card-title` | Title text (lg, semibold, tight tracking) |
| `.card-description` | Subtitle text (sm, muted) |
| `.card-content` | Main content area with padding |
| `.card-footer` | Footer with top border |

```html
<div class="card">
  <div class="card-header">
    <h3 class="card-title">Templates</h3>
    <p class="card-description">Manage your document templates</p>
  </div>
  <div class="card-content">...</div>
</div>
```

---

## Navigation

### Top Navigation Bar

Full-width header bar for primary navigation.

```
Container:
  h-14 bg-white border-b border-gray-200
  px-4 flex items-center justify-between

Logo area:
  flex items-center gap-3
  Logo: h-8 w-auto
  App name: text-lg font-semibold text-gray-900

Nav links:
  flex items-center gap-1
  Link: px-3 py-2 text-sm text-gray-600 hover:text-gray-900 hover:bg-gray-50 rounded-lg
  Active link: text-blue-600 bg-blue-50

Right section:
  flex items-center gap-2
```

### Sidebar Navigation

Vertical navigation for main app sections.

```
Container:
  w-64 bg-white border-r border-gray-200
  flex flex-col h-full

Header:
  h-14 px-4 flex items-center border-b border-gray-200
  Logo + App name

Nav section:
  flex-1 py-4 px-3 space-y-1 overflow-y-auto

Nav item:
  flex items-center gap-3 px-3 py-2 rounded-lg
  text-sm text-gray-600
  hover:bg-gray-50 hover:text-gray-900
  transition-colors

Nav item (active):
  bg-blue-50 text-blue-600 font-medium

Nav item icon:
  w-5 h-5 text-gray-400
  Active: text-blue-500

Nav group label:
  px-3 py-2 text-xs font-medium text-gray-400 uppercase tracking-wider

Footer:
  p-4 border-t border-gray-200
```

### Hamburger Menu Button

Toggle button for mobile navigation.

```
Button:
  p-2 rounded-lg
  text-gray-500 hover:text-gray-700 hover:bg-gray-100
  transition-colors

Icon (closed - three lines):
  w-6 h-6
  Three horizontal lines: h-0.5 w-6 bg-current
  Spacing: space-y-1.5

Icon (open - X):
  w-6 h-6
  Two diagonal lines forming X
  rotate-45 and -rotate-45
```

### Mobile Navigation Drawer

Slide-out navigation for mobile.

```
Overlay:
  fixed inset-0 z-40 bg-black/25

Drawer:
  fixed inset-y-0 left-0 z-50 w-64
  bg-white shadow-xl
  transform transition-transform duration-200
  Closed: -translate-x-full
  Open: translate-x-0

Close button:
  absolute top-4 right-4
  p-1 rounded text-gray-400 hover:text-gray-600
```

### Breadcrumbs

Show navigation hierarchy.

```
Container:
  flex items-center gap-2 text-sm

Item:
  text-gray-500 hover:text-gray-700

Separator:
  text-gray-300
  Content: "/" or chevron icon (w-4 h-4)

Current (last item):
  text-gray-900 font-medium
  No hover effect, not clickable
```

---

## Menus

### Dropdown Menu

Triggered by button click.

```
Trigger button:
  (Use any button style)
  With chevron-down icon (w-4 h-4 ml-1)

Menu container:
  absolute mt-1 z-20
  bg-white rounded-lg shadow-lg border border-gray-200
  py-1 min-w-[160px]

Menu item:
  w-full px-3 py-2 text-left text-sm text-gray-700
  hover:bg-gray-50
  flex items-center gap-2

Menu item with icon:
  Icon: w-4 h-4 text-gray-400

Menu item (danger):
  text-red-600 hover:bg-red-50

Divider:
  my-1 border-t border-gray-200

Menu header/label:
  px-3 py-2 text-xs font-medium text-gray-400 uppercase
```

### Context Menu

Right-click triggered menu.

```
Container:
  fixed z-50
  bg-white rounded-lg shadow-xl border border-gray-200
  py-1 min-w-[180px]

(Same item styles as Dropdown Menu)
```

### Action Menu (three dots)

Compact actions menu.

```
Trigger:
  p-1 rounded hover:bg-gray-100
  Icon: w-5 h-5 text-gray-400
  Content: ••• (vertical dots)

Menu:
  (Same as Dropdown Menu)
```

### User Menu

Account/profile dropdown.

```
Trigger:
  flex items-center gap-2 px-2 py-1 rounded-lg
  hover:bg-gray-50

Avatar:
  w-8 h-8 rounded-full

User name (optional):
  text-sm text-gray-700

Chevron:
  w-4 h-4 text-gray-400

Menu:
  (Same as Dropdown Menu)
  Include: Profile, Settings, divider, Sign out
```

---

## Data Tables

### Table Container

```
Wrapper:
  bg-white rounded-lg border border-gray-200 overflow-hidden

Scrollable:
  overflow-x-auto
```

### Table Header

```
Header row:
  bg-gray-50 border-b border-gray-200

Header cell:
  px-4 py-3 text-left
  text-xs font-medium text-gray-500 uppercase tracking-wider

Sortable header:
  cursor-pointer hover:bg-gray-100
  flex items-center gap-1

Sort icon:
  w-4 h-4 text-gray-400
  Active: text-blue-500
```

### Table Body

```
Row:
  border-b border-gray-200 last:border-0
  hover:bg-gray-50 transition-colors

Row (selected):
  bg-blue-50

Row (clickable):
  cursor-pointer

Cell:
  px-4 py-3 text-sm text-gray-700

Cell (numeric):
  text-right font-mono

Cell (truncate):
  max-w-[200px] truncate
```

### Table Actions

```
Cell with actions:
  px-4 py-2

Action buttons:
  flex items-center gap-1

Action button:
  p-1 rounded text-gray-400 hover:text-gray-600 hover:bg-gray-100
```

### Table Footer

```
Footer:
  px-4 py-3 bg-gray-50 border-t border-gray-200
  flex items-center justify-between

Row count:
  text-sm text-gray-500

Pagination:
  (See Pagination component)
```

### Empty Table

```
Container:
  py-12 text-center

Icon:
  mx-auto w-12 h-12 text-gray-300

Message:
  mt-4 text-sm text-gray-500

Action:
  mt-4
  (Primary button)
```

---

## Cards

### Basic Card

```
Container:
  bg-white rounded-lg border border-gray-200
  overflow-hidden

With shadow:
  shadow-sm hover:shadow-md transition-shadow

Clickable:
  cursor-pointer hover:border-gray-300
```

### Card Header

```
Header:
  px-4 py-3 border-b border-gray-200
  flex items-center justify-between

Title:
  text-sm font-medium text-gray-900

Actions:
  flex items-center gap-2
```

### Card Body

```
Body:
  p-4
```

### Card Footer

```
Footer:
  px-4 py-3 bg-gray-50 border-t border-gray-200
  flex items-center justify-between (or justify-end)
```

### Stat Card (Dashboard)

```
Container:
  bg-white rounded-lg border border-gray-200 p-4

Label:
  text-sm text-gray-500

Value:
  mt-1 text-2xl font-semibold text-gray-900

Change indicator:
  mt-2 flex items-center gap-1 text-sm
  Positive: text-green-600
  Negative: text-red-600
  Neutral: text-gray-500

Icon:
  w-4 h-4
  Up arrow / Down arrow / Minus
```

### Stat Card with Icon

```
Container:
  bg-white rounded-lg border border-gray-200 p-4
  flex items-start gap-4

Icon container:
  p-3 rounded-lg
  Blue: bg-blue-100 text-blue-600
  Green: bg-green-100 text-green-600
  Amber: bg-amber-100 text-amber-600
  Purple: bg-purple-100 text-purple-600

Icon:
  w-6 h-6

Content:
  flex-1
  (Label, Value, Change as above)
```

### Content Card

```
Container:
  bg-white rounded-lg border border-gray-200 overflow-hidden

Image (optional):
  w-full h-40 object-cover

Body:
  p-4

Title:
  text-base font-medium text-gray-900

Description:
  mt-1 text-sm text-gray-500 line-clamp-2

Meta:
  mt-3 flex items-center gap-4 text-xs text-gray-400
```

### List Card

```
Container:
  bg-white rounded-lg border border-gray-200

Header:
  px-4 py-3 border-b border-gray-200
  flex items-center justify-between

List:
  divide-y divide-gray-200

List item:
  px-4 py-3 flex items-center justify-between
  hover:bg-gray-50

Footer:
  px-4 py-3 border-t border-gray-200 text-center
  Link: text-sm text-blue-600 hover:text-blue-700
```

---

## Lists

### Simple List

```
Container:
  divide-y divide-gray-200

Item:
  py-3 flex items-center justify-between
```

### List with Icons

```
Item:
  py-3 flex items-center gap-3

Icon:
  w-5 h-5 text-gray-400

Content:
  flex-1

Title:
  text-sm font-medium text-gray-900

Description:
  text-sm text-gray-500
```

### Action List

```
Item:
  py-2 px-3 rounded-lg
  flex items-center gap-3
  cursor-pointer
  hover:bg-gray-50 transition-colors

Item (active):
  bg-blue-50 text-blue-600

Item (danger hover):
  hover:bg-red-50 hover:text-red-600
```

### Stacked List

```
Item:
  p-4 border-b border-gray-200 last:border-0
  hover:bg-gray-50

Title row:
  flex items-center justify-between

Title:
  text-sm font-medium text-gray-900

Badge/Status:
  (See Badge component)

Description:
  mt-1 text-sm text-gray-500

Meta row:
  mt-2 flex items-center gap-4 text-xs text-gray-400
```

---

## Feedback

### Toast Notification

Temporary feedback messages.

```
Container:
  fixed bottom-4 right-4 z-50
  flex flex-col gap-2

Toast:
  px-4 py-3 rounded-lg shadow-lg
  flex items-center gap-3
  min-w-[300px] max-w-[400px]

Success:
  bg-green-50 border border-green-200
  Icon: text-green-500
  Text: text-green-800

Error:
  bg-red-50 border border-red-200
  Icon: text-red-500
  Text: text-red-800

Warning:
  bg-amber-50 border border-amber-200
  Icon: text-amber-500
  Text: text-amber-800

Info:
  bg-blue-50 border border-blue-200
  Icon: text-blue-500
  Text: text-blue-800

Icon:
  w-5 h-5 flex-shrink-0

Message:
  flex-1 text-sm

Close button:
  p-1 rounded hover:bg-black/5
  Icon: w-4 h-4 text-current opacity-50
```

### Inline Alert

Static alert within content.

```
Container:
  p-4 rounded-lg flex gap-3

Success:
  bg-green-50 border border-green-200

Error:
  bg-red-50 border border-red-200

Warning:
  bg-amber-50 border border-amber-200

Info:
  bg-blue-50 border border-blue-200

Icon:
  w-5 h-5 flex-shrink-0
  Matches alert type color

Content:
  flex-1

Title (optional):
  font-medium text-sm

Message:
  text-sm mt-1 (if title) or text-sm (if no title)
```

### Banner

Full-width notification bar.

```
Container:
  px-4 py-3
  flex items-center justify-center gap-3

Info: bg-blue-600 text-white
Warning: bg-amber-500 text-white
Error: bg-red-600 text-white
Success: bg-green-600 text-white

Icon:
  w-5 h-5

Message:
  text-sm font-medium

Action link:
  text-sm font-medium underline hover:no-underline

Dismiss:
  p-1 rounded hover:bg-white/20
```

### Empty State

No data or results to display.

```
Container:
  py-12 px-4 text-center

Icon:
  mx-auto w-16 h-16 text-gray-300

Title:
  mt-4 text-lg font-medium text-gray-900

Description:
  mt-2 text-sm text-gray-500 max-w-sm mx-auto

Action:
  mt-6
  (Primary button)
```

### Loading State

#### Spinner

```
Spinner:
  animate-spin rounded-full border-2
  border-gray-200 border-t-blue-500

Sizes:
  Small: w-4 h-4
  Medium: w-6 h-6
  Large: w-8 h-8
```

#### Skeleton

```
Skeleton:
  bg-gray-200 rounded animate-pulse

Text line:
  h-4 rounded

Heading:
  h-6 rounded

Avatar:
  rounded-full

Image:
  rounded-lg
```

#### Loading Overlay

```
Overlay:
  absolute inset-0 bg-white/75 flex items-center justify-center
  z-10

Spinner + text:
  flex flex-col items-center gap-2
  Text: text-sm text-gray-500
```

### Progress Bar

```
Container:
  w-full bg-gray-200 rounded-full overflow-hidden

Bar:
  h-2 bg-blue-500 rounded-full
  transition-all duration-300

With label:
  flex items-center justify-between mb-1
  Label: text-sm text-gray-700
  Percentage: text-sm text-gray-500
```

---

## Utility Components

### Avatar

```
Base:
  rounded-full bg-gray-200 flex items-center justify-center
  overflow-hidden

Sizes:
  XS: w-6 h-6 text-xs
  SM: w-8 h-8 text-sm
  MD: w-10 h-10 text-base
  LG: w-12 h-12 text-lg
  XL: w-16 h-16 text-xl

With image:
  img: w-full h-full object-cover

With initials:
  text-gray-600 font-medium
  Background colors (rotated by name):
    bg-blue-100, bg-green-100, bg-amber-100,
    bg-purple-100, bg-pink-100, bg-cyan-100

With status:
  relative
  Status dot: absolute bottom-0 right-0
    w-3 h-3 rounded-full border-2 border-white
    Online: bg-green-500
    Away: bg-amber-500
    Offline: bg-gray-400
```

### Avatar Group

```
Container:
  flex -space-x-2

Avatar:
  ring-2 ring-white

Overflow indicator:
  w-8 h-8 rounded-full bg-gray-100
  flex items-center justify-center
  text-xs text-gray-600 font-medium
  ring-2 ring-white
```

### Tooltip

```
Trigger:
  relative inline-block

Tooltip:
  absolute z-50
  px-2 py-1 rounded
  bg-gray-900 text-white text-xs
  whitespace-nowrap

  Positions:
    Top: bottom-full left-1/2 -translate-x-1/2 mb-1
    Bottom: top-full left-1/2 -translate-x-1/2 mt-1
    Left: right-full top-1/2 -translate-y-1/2 mr-1
    Right: left-full top-1/2 -translate-y-1/2 ml-1

Arrow:
  absolute w-2 h-2 bg-gray-900 rotate-45
```

### Popover

```
Trigger:
  (Any clickable element)

Popover:
  absolute z-50 mt-2
  bg-white rounded-lg shadow-xl border border-gray-200
  min-w-[200px]

Header (optional):
  px-4 py-3 border-b border-gray-200
  font-medium text-sm text-gray-900

Content:
  p-4

Footer (optional):
  px-4 py-3 border-t border-gray-200 bg-gray-50
```

### Pagination

```
Container:
  flex items-center gap-1

Page button:
  min-w-[32px] h-8 px-2
  text-sm text-gray-600
  rounded hover:bg-gray-100
  flex items-center justify-center

Page button (active):
  bg-blue-500 text-white hover:bg-blue-600

Page button (disabled):
  text-gray-300 cursor-not-allowed hover:bg-transparent

Prev/Next:
  px-3 h-8 text-sm text-gray-600
  rounded hover:bg-gray-100
  flex items-center gap-1

Ellipsis:
  px-2 text-gray-400
```

### Search Input

```
Container:
  relative

Input:
  w-full pl-10 pr-4 py-2 text-sm
  border border-gray-200 rounded-lg
  focus:outline-none focus:border-blue-400

Search icon:
  absolute left-3 top-1/2 -translate-y-1/2
  w-4 h-4 text-gray-400

Clear button (when has value):
  absolute right-3 top-1/2 -translate-y-1/2
  p-1 rounded hover:bg-gray-100
  Icon: w-4 h-4 text-gray-400
```

### Search with Dropdown

```
Container:
  relative

(Search Input as above)

Results dropdown:
  absolute top-full left-0 right-0 mt-1 z-50
  bg-white rounded-lg shadow-lg border border-gray-200
  max-h-[300px] overflow-y-auto

Result item:
  px-4 py-2 hover:bg-gray-50 cursor-pointer
  text-sm

Result item (highlighted):
  bg-blue-50

No results:
  px-4 py-8 text-center text-sm text-gray-500
```

### Tag/Chip

```
Base:
  inline-flex items-center gap-1
  px-2 py-0.5 rounded-full text-xs font-medium

Variants:
  Gray: bg-gray-100 text-gray-700
  Blue: bg-blue-100 text-blue-700
  Green: bg-green-100 text-green-700
  Amber: bg-amber-100 text-amber-700
  Red: bg-red-100 text-red-700
  Purple: bg-purple-100 text-purple-700

With remove button:
  pr-1
  Button: p-0.5 rounded-full hover:bg-black/10
  Icon: w-3 h-3
```

### Divider

```
Horizontal:
  border-t border-gray-200

With text:
  flex items-center gap-4
  Line: flex-1 border-t border-gray-200
  Text: text-sm text-gray-500

Vertical:
  border-l border-gray-200 h-full
```

### Checkbox

```
Container:
  flex items-center gap-2

Input:
  w-4 h-4 rounded border-gray-300
  text-blue-500 focus:ring-blue-500

Label:
  text-sm text-gray-700
```

### Radio Button

```
Container:
  flex items-center gap-2

Input:
  w-4 h-4 border-gray-300
  text-blue-500 focus:ring-blue-500

Label:
  text-sm text-gray-700
```

### Toggle Switch

```
Container:
  relative inline-flex items-center cursor-pointer

Track:
  w-10 h-6 rounded-full transition-colors
  Off: bg-gray-200
  On: bg-blue-500

Thumb:
  absolute w-4 h-4 bg-white rounded-full shadow
  transition-transform
  Off: left-1 (translate-x-0)
  On: left-1 (translate-x-4)
```

---

## Interactive States

### Hover

| Element | Effect |
|---------|--------|
| Primary button | `bg-blue-500` → `bg-blue-600` |
| Secondary button | `bg-gray-100` → `bg-gray-200` |
| Block | `border-transparent` → `border-gray-300` |
| Palette item | `border-gray-200` → `border-blue-400` |
| Section header | Background `bg-gray-50` |
| Expression chip | `#dbeafe` → `#bfdbfe` |

### Focus

Focus uses a **ring-based system** via offset `box-shadow`, not border-color. This provides a visible focus indicator that does not shift layout.

```css
/* Focus ring implementation (from --ep-ring) */
box-shadow:
  0 0 0 var(--ep-ring-offset) var(--ep-white),        /* white gap */
  0 0 0 calc(var(--ep-ring-offset) + var(--ep-ring-width)) var(--ep-ring-color);  /* blue ring */

/* Default values: --ep-ring-offset: 2px, --ep-ring-width: 2px, --ep-ring-color: blue-500 */
```

| Element | Effect |
|---------|--------|
| Text input | Border changes to `--ep-ring-color` + focus ring box-shadow |
| Select | Border changes to `--ep-ring-color` + focus ring box-shadow |
| Textarea | Border changes to `--ep-ring-color` + focus ring box-shadow |
| Button | Focus ring box-shadow on `:focus-visible` (no change on click) |
| Destructive button | Focus ring with `--ep-red-500` instead of blue |

### Active/Selected

| Element | Effect |
|---------|--------|
| Block | `border-2 border-blue-500 shadow-md` |
| Tab | `text-blue-600 border-b-2 border-blue-600 bg-blue-50/50` |
| Toggle button | `bg-blue-500 text-white` |
| Formatting button | `bg-blue-500 text-white` |

### Disabled

| Element | Effect |
|---------|--------|
| Input | `bg-gray-50` |
| Text | `text-gray-400` |
| Button | `bg-gray-100 text-gray-500` |

### Dragging

| Element | Effect |
|---------|--------|
| Block being dragged | `opacity-50` |
| Drag handle | `opacity-0` → `opacity-100` on hover |
| Drop zone (active) | `bg-blue-50` or block-specific color |
| Drop indicator | `bg-blue-400 h-4` |

### Error

| Element | Effect |
|---------|--------|
| Input border | `border-red-400 focus:border-red-500` |
| Block background | `bg-red-50` |
| Text | `text-red-600` |

---

## Layout

### Main Layout

```
[Header - h-14, border-b]
[Main Content - flex-1, flex, gap-2, p-2]
  [Left Sidebar - w-64]
  [Center Editor - flex-1]
  [Right Preview - flex-1]
```

### Sidebar

```
[Header - px-3 py-2, border-b, bg-gray-50]
[Tabs - flex, border-b]
[Content - flex-1, overflow-y-auto]
```

### Grid Patterns

| Pattern | Tailwind |
|---------|----------|
| Two columns | `grid grid-cols-2 gap-2` |
| Three columns | `grid grid-cols-3 gap-1` |
| Four columns | `grid grid-cols-4 gap-2` |

### Flex Patterns

| Pattern | Tailwind |
|---------|----------|
| Row centered | `flex items-center gap-2` |
| Row spread | `flex items-center justify-between` |
| Column | `flex flex-col gap-2` |
| Wrap | `flex flex-wrap gap-2` |

---

## Icons

All icons use [Lucide](https://lucide.dev/) -- an open-source icon set with consistent 24x24 stroke-based SVGs.

### Delivery Methods

There are **two** ways to use Lucide icons, depending on the rendering context:

#### 1. SVG Sprite (Thymeleaf / server-side templates)

A build-time sprite (`modules/design-system/icons.svg`) is generated from selected Lucide icons. Reference icons via `<use href>` in Thymeleaf.

```html
<!-- Thymeleaf fragment (fragments/icon.html) -->
<th:block th:replace="~{fragments/icon :: icon(name='file-text', class='ep-icon')}" />
<th:block th:replace="~{fragments/icon :: icon(name='plus', class='ep-icon ep-icon-lg')}" />

<!-- Rendered output -->
<svg class="ep-icon" aria-hidden="true">
  <use href="/design-system/icons.svg#icon-file-text" />
</svg>
```

#### 2. Inline SVG helper (Lit / TypeScript components)

For Lit web components, use the `icon()` helper from `ui/icons.ts`. Icons are baked into the bundle as inline SVG templates -- no runtime HTTP requests.

```typescript
import { icon } from '../ui/icons'

// In a Lit template
html`<button>${icon('undo-2')} Undo</button>`
html`<button>${icon('plus', 20)} Add</button>`  // custom size (default: 16)
```

### Icon Size Classes

| Class | Size | Usage |
|-------|------|-------|
| `.ep-icon-sm` | 0.875rem (14px) | Compact UI, inline with small text |
| `.ep-icon` | 1rem (16px) | Default — buttons, labels, list items |
| `.ep-icon-lg` | 1.25rem (20px) | Section headers, prominent actions |
| `.ep-icon-xl` | 1.5rem (24px) | Empty states, large feature icons |

### Commonly Used Icons

| Name | Lucide Icon | Usage |
|------|-------------|-------|
| `file-text` | FileText | Document/template |
| `layout-template` | LayoutTemplate | Container block |
| `columns-2` | Columns2 | Column block |
| `table` | Table | Table block |
| `git-branch` | GitBranch | Conditional block |
| `repeat` | Repeat | Loop block |
| `type` | Type | Text block |
| `plus` | Plus | Add actions |
| `trash-2` | Trash2 | Delete actions |
| `save` | Save | Save actions |
| `undo-2` | Undo2 | Undo |
| `redo-2` | Redo2 | Redo |
| `search` | Search | Search input |
| `x` | X | Close / dismiss |
| `chevron-right` | ChevronRight | Expand / navigate |
| `grip-vertical` | GripVertical | Drag handle |
| `box` | Box | Generic block |

---

## Transitions

Three speed levels are available as CSS custom properties:

| Name | CSS Variable | Duration | Usage |
|------|-------------|----------|-------|
| Fast | `--ep-transition-fast` | 100ms ease | Table row hover, quick micro-interactions |
| Base | `--ep-transition-base` | 150ms ease | Buttons, hover states, color changes |
| Slow | `--ep-transition-slow` | 200ms ease | Panels, overlays, larger UI shifts |

A composite `--ep-transition-colors` property transitions `color`, `background-color`, `border-color`, and `box-shadow` at the base speed. This is the default for buttons and form controls.

```css
/* Usage examples */
transition: var(--ep-transition-colors);          /* color properties at 150ms */
transition: background-color var(--ep-transition-fast);  /* single property, fast */
transition: all var(--ep-transition-slow);         /* all properties, slow */
```

---

## Z-Index

| Layer | Z-Index | CSS Variable | Usage |
|-------|---------|-------------|-------|
| Base | 0 | — | Normal content flow |
| Dropdown | 10 | `--ep-z-dropdown` | Select menus, dropdown panels |
| Sticky | 20 | `--ep-z-sticky` | Sticky headers, toolbars |
| Overlay | 40 | `--ep-z-overlay` | Modal backdrop, drawer overlay |
| Modal | 50 | `--ep-z-modal` | Modal dialog, context menu |
| Tooltip | 60 | `--ep-z-tooltip` | Tooltips, popovers |

---

## Accessibility

### Color Contrast

- Primary text (`gray-700` on white): 8.5:1
- Secondary text (`gray-500` on white): 4.6:1
- Error text (`red-600` on white): 4.5:1
- Blue on white (`blue-600`): 4.5:1

### Focus Indicators

- All interactive elements: Ring-based focus via `box-shadow` (2px blue-500 ring with 2px white offset)
- Inputs: Border transitions to `--ep-ring-color` + focus ring shadow
- Buttons: Focus ring on `:focus-visible` only (not on click)

### Touch Targets

- Minimum button size: 32px (w-8 h-8)
- Comfortable tap target: 44px recommended

---

## Version

| Version | Date | Changes |
|---------|------|---------|
| 2.0.0 | 2026-02 | Comprehensive UI redesign: Inter font, enhanced tokens, CSS component classes, Lucide icons, ring-based focus, shadcn/ui-inspired design |
| 1.1.0 | 2025-12 | Added app-wide components: Navigation, Menus, Data Tables, Cards, Lists, Feedback, Utility components |
| 1.0.0 | 2025-12 | Initial visual styleguide for template editor |
