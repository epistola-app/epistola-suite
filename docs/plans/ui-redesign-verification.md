# UI Redesign Verification Checklist

## How to Run
```bash
pnpm install && pnpm build && ./gradlew build
./gradlew :apps:epistola:bootRun
```

Then check each page visually at http://localhost:8080

## Main App Pages

### Login Page
- [ ] Inter font loads correctly
- [ ] Login card centered with proper shadow
- [ ] Form inputs use ring-based focus
- [ ] "Sign In" button uses .btn .btn-primary with Lucide log-in icon
- [ ] OAuth button uses .btn .btn-outline
- [ ] Alert components render correctly (error, success, warning, info)
- [ ] Login divider renders between form and OAuth

### Tenant List (/)
- [ ] Table uses .ep-table styling with border-radius
- [ ] Create tenant form uses .ep-input and .btn .btn-primary
- [ ] Search input styled correctly
- [ ] Footer is sticky at bottom

### Tenant Home (/tenants/{id})
- [ ] Navigation cards display with Lucide icons in icon circles
- [ ] Cards have hover lift effect with border transition
- [ ] Breadcrumb navigation renders correctly
- [ ] Tenant header shows name and ID

### Template List
- [ ] Table rows with proper hover state
- [ ] Action buttons use correct hierarchy (.btn .btn-sm)
- [ ] Search fragment works

### Template Detail
- [ ] Badges use new classes: .badge .badge-primary (draft), .badge .badge-success (published), .badge .badge-outline (archived)
- [ ] Form controls use .ep-input, .ep-select
- [ ] Action buttons have proper hierarchy

### Theme List
- [ ] Table styling matches template list
- [ ] Buttons use component classes

### Theme Detail
- [ ] Form controls use .ep-input, .ep-select
- [ ] Proper card-like layout

### Environment List
- [ ] Table and button styling consistent

### Load Test Pages (list, new, detail, requests)
- [ ] HTMX loads from shared fragment (not CDN)
- [ ] Badges use new component classes
- [ ] Metric cards have subtle borders
- [ ] Progress bar is rounded
- [ ] Alert components render correctly

### Session Dialogs
- [ ] Dialog backdrop has blur effect
- [ ] Buttons use .btn classes

## Editor (editor-v2)

### Toolbar
- [ ] Subtle bottom shadow (not hard border)
- [ ] Undo/redo buttons show Lucide SVG icons
- [ ] Separator divider between title and actions
- [ ] Button hover shows border transition
- [ ] Disabled state dims correctly
- [ ] Example selector dropdown styled with ring focus

### Palette Panel
- [ ] Category labels are 11px uppercase with letter-spacing
- [ ] Palette items have icon circles (gray-100 bg)
- [ ] Hover shows subtle shadow lift (shadow-xs)
- [ ] Each block type shows correct Lucide icon

### Tree Panel
- [ ] Selected node has blue-50 bg with inset 2px blue-500 left accent border
- [ ] Smooth hover transitions
- [ ] Tree nodes show correct Lucide icons per type
- [ ] Root node icon differs from child nodes

### Canvas
- [ ] Page shadow upgraded to shadow-md
- [ ] Block headers have linear-gradient (gray-50 to gray-100)
- [ ] Selected blocks show 2px blue-500 ring (not double border trick)
- [ ] Hover shows 1px gray-300 outline

### Inspector
- [ ] Node info section has gray-50 background
- [ ] Section labels are 11px uppercase with letter-spacing
- [ ] Inputs are compact (h-8 height)
- [ ] Delete section has subtle border-top separator
- [ ] Style color picker borders match design system

### ProseMirror / Rich Text
- [ ] Expression chips have pill radius (rounded-full) with shadow-xs
- [ ] Chip hover shows shadow-sm upgrade
- [ ] Bubble menu has shadow-lg elevation
- [ ] Expression dialog backdrop has blur(4px)
- [ ] No fallback values visible in computed styles

### Drag and Drop
- [ ] Drop indicators show blue-500 lines
- [ ] Slot highlight on drag-over
- [ ] Drag source dims to 0.4 opacity
- [ ] Tree drop indicators work (reorder-above, reorder-below, make-child)

## Cross-cutting

- [ ] All pages use Inter font
- [ ] No hardcoded color values in main.css
- [ ] Focus ring visible on all interactive elements (tab navigation)
- [ ] No broken icon references (check browser console for 404s)
- [ ] Consistent spacing and typography across all pages
- [ ] Design system tokens.css loads on all pages
- [ ] Lucide SVG sprite loads successfully (/design-system/icons.svg)
