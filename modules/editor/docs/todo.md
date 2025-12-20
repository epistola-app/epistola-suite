# Template Editor - Next Steps

A prioritized roadmap to make this editor production-ready.

---

## Phase 1: Core Stability

Essential fixes before adding features.

### 1.1 Sandboxed Expression Evaluation ✓ COMPLETED
**Priority:** Critical
**Effort:** Medium

~~Replace `new Function()` with QuickJS-WASM for safe expression execution.~~

Implemented using iframe sandbox approach instead:

- [x] Create expression evaluator interface (`src/services/expression/types.ts`)
- [x] Implement DirectEvaluator (fast, unsandboxed)
- [x] Implement IframeEvaluator (sandboxed iframe with postMessage)
- [x] Add execution timeout (1 second for infinite loop protection)
- [x] Update Preview.tsx to use evaluator service
- [x] Update ExpressionEditor.tsx to use evaluator service
- [x] Update ExpressionNode.tsx to use evaluator service
- [x] Add evaluator switcher in header UI
- [ ] Inject safe helper functions (formatDate, formatCurrency, formatNumber, etc.) → TODO

### 1.2 Undo/Redo
**Priority:** High
**Effort:** Medium

Users expect Ctrl+Z to work.

- [ ] Add history array to Zustand store
- [ ] Track state snapshots on each change
- [ ] Implement undo action (pop from history)
- [ ] Implement redo action (separate redo stack)
- [ ] Add keyboard shortcuts (Ctrl+Z, Ctrl+Shift+Z)
- [ ] Add undo/redo buttons to toolbar
- [ ] Limit history size (e.g., 50 states)

### 1.3 Template Persistence
**Priority:** High
**Effort:** Medium

Save and load templates.

- [ ] Define API endpoints (GET/POST/PUT /templates)
- [ ] Add save button to editor header
- [ ] Implement auto-save with debounce
- [ ] Add template name/metadata editing
- [ ] Handle save errors gracefully
- [ ] Add "unsaved changes" indicator
- [ ] Confirm before leaving with unsaved changes

### 1.4 Test Data Management
**Priority:** High
**Effort:** Low

Allow users to edit test data.

- [ ] Add JSON editor panel (Monaco or CodeMirror)
- [ ] Validate JSON on change
- [ ] Persist test data with template
- [ ] Add sample data presets
- [ ] Show validation errors clearly

---

## Phase 2: Essential Features

Features needed for real-world use.

### 2.1 Table Block
**Priority:** High
**Effort:** High

Tables are essential for invoices, reports, etc.

- [ ] Create TableBlock type in template.ts
- [ ] Build TableBlock component
- [ ] Support static rows/columns
- [ ] Support dynamic rows from array expression
- [ ] Expression support in cells
- [ ] Column width configuration
- [ ] Header row styling
- [ ] Add to block palette
- [ ] Update Preview.tsx to render tables

### 2.2 Image Block
**Priority:** Medium
**Effort:** Medium

Support logos, signatures, product images.

- [ ] Create ImageBlock type
- [ ] Build ImageBlock component
- [ ] URL input with expression support
- [ ] Image upload to storage
- [ ] Size controls (width, height, aspect ratio)
- [ ] Alignment options (left, center, right)
- [ ] Alt text for accessibility
- [ ] Update Preview.tsx to render images

### 2.3 Row/Column Layout
**Priority:** Medium
**Effort:** Medium

Multi-column layouts for headers, footers, etc.

- [ ] Create RowBlock type with column configuration
- [ ] Build RowBlock component
- [ ] Support 2, 3, 4 column layouts
- [ ] Custom column width ratios
- [ ] Vertical alignment options
- [ ] Gap/spacing controls
- [ ] Update Preview.tsx for row layouts

### 2.4 Data Schema Definition
**Priority:** Medium
**Effort:** Medium

Define expected data structure for better autocomplete.

- [ ] Create schema editor UI
- [ ] Support JSON Schema format
- [ ] Generate TypeScript types from schema
- [ ] Validate test data against schema
- [ ] Show schema-aware autocomplete
- [ ] Highlight expression errors for missing fields

---

## Phase 3: User Experience

Polish and productivity improvements.

### 3.1 Improved Drag & Drop
**Priority:** Medium
**Effort:** Medium

- [ ] Better visual drop indicators
- [ ] Highlight valid drop zones
- [ ] Show nesting depth limits
- [ ] Drag preview showing block content
- [ ] Keyboard-based reordering (Alt+Up/Down)

### 3.2 Block Operations
**Priority:** Medium
**Effort:** Low

- [ ] Copy block (Ctrl+C)
- [ ] Paste block (Ctrl+V)
- [ ] Duplicate block (Ctrl+D)
- [ ] Cut block (Ctrl+X)
- [ ] Multi-select blocks
- [ ] Bulk delete

### 3.3 Keyboard Shortcuts
**Priority:** Medium
**Effort:** Low

- [ ] Document all shortcuts in help modal
- [ ] Add block (Ctrl+Enter)
- [ ] Delete block (Delete/Backspace)
- [ ] Navigate blocks (Arrow keys)
- [ ] Escape to deselect

### 3.4 Properties Panel ✓ COMPLETED
**Priority:** Medium
**Effort:** Medium

Dedicated panel for block configuration.

- [x] Show when block selected
- [x] Block-specific settings (tips per block type)
- [x] Style controls (padding, margin, background, typography, borders, effects, layout)
- [ ] CSS class input for advanced users
- [ ] Preset styles/themes

Implemented as a sidebar with two tabs:
- **Properties tab**: Block type, ID, contextual tips; Document properties with editable margins
- **Styles tab**: Full CSS subset with 6 categories (Spacing, Typography, Background, Borders, Effects, Layout)

See `docs/styling.md` for full documentation.

### 3.5 Better Expression Editor
**Priority:** Medium
**Effort:** Medium

- [ ] Syntax highlighting
- [ ] Multi-line support for complex expressions
- [ ] Function documentation on hover
- [ ] Recently used expressions
- [ ] Expression templates/snippets

---

## Phase 4: PDF Integration

Connect to actual PDF generation.

### 4.1 Puppeteer Integration
**Priority:** High
**Effort:** Medium

- [ ] API endpoint for PDF generation
- [ ] Send template + data to backend
- [ ] Return generated PDF
- [ ] Show PDF in preview iframe
- [ ] Download PDF button

### 4.2 Page Settings UI (Partial)
**Priority:** Medium
**Effort:** Low

- [ ] Page format selector (A4, Letter, etc.)
- [ ] Orientation toggle
- [x] Margin controls (implemented in Document Properties panel)
- [ ] Preview page boundaries

### 4.3 Page Breaks
**Priority:** Medium
**Effort:** Medium

- [ ] Page break block type
- [ ] "Keep together" option for blocks
- [ ] "Avoid break inside" option
- [ ] Page break preview indicators

### 4.4 Headers & Footers
**Priority:** Medium
**Effort:** High

- [ ] Separate header/footer template areas
- [ ] Page number expression (`{{pageNumber}}`, `{{totalPages}}`)
- [ ] Different first page header/footer
- [ ] Odd/even page variations

---

## Phase 5: Advanced Features

Nice-to-have for power users.

### 5.1 Template Variables
**Priority:** Low
**Effort:** Medium

Define reusable values within template.

- [ ] Variable definition UI
- [ ] Reference variables in expressions
- [ ] Computed variables (expressions)

### 5.2 Conditional Styling
**Priority:** Low
**Effort:** Medium

Dynamic styles based on data.

- [ ] Style expressions (e.g., `color: {{total < 0 ? 'red' : 'black'}}`)
- [ ] Conditional CSS classes
- [ ] Theme variables

### 5.3 Template Inheritance
**Priority:** Low
**Effort:** High

Base templates with overridable sections.

- [ ] Define template slots
- [ ] Extend base template
- [ ] Override specific blocks

### 5.4 Version History
**Priority:** Low
**Effort:** Medium

- [ ] Save versions on each save
- [ ] Version list with timestamps
- [ ] Preview old versions
- [ ] Restore previous version
- [ ] Diff view between versions

### 5.5 Collaboration
**Priority:** Low
**Effort:** High

Real-time multi-user editing.

- [ ] WebSocket connection
- [ ] Operational transformation or CRDT
- [ ] User presence indicators
- [ ] Cursor positions
- [ ] Change attribution

---

## Phase 6: Production Readiness

Before going live.

### 6.1 Testing
**Priority:** High
**Effort:** High

- [ ] Unit tests for expression evaluation
- [ ] Unit tests for block operations
- [ ] Component tests for each block type
- [ ] Integration tests for editor flow
- [ ] E2E tests with Playwright/Cypress
- [ ] Visual regression tests

### 6.2 Error Handling
**Priority:** High
**Effort:** Medium

- [ ] Error boundaries around blocks
- [ ] Graceful expression error display
- [ ] Network error handling
- [ ] Auto-recovery from crashes
- [ ] Error reporting/logging

### 6.3 Performance
**Priority:** Medium
**Effort:** Medium

- [ ] Virtualize long block lists
- [ ] Lazy load heavy components
- [ ] Debounce preview updates
- [ ] Memoize expensive computations
- [ ] Profile and optimize renders

### 6.4 Accessibility
**Priority:** Medium
**Effort:** Medium

- [ ] Keyboard navigation throughout
- [ ] Screen reader labels
- [ ] Focus management
- [ ] Color contrast compliance
- [ ] Reduced motion support

### 6.5 Documentation
**Priority:** Medium
**Effort:** Medium

- [ ] User guide
- [ ] Expression function reference
- [ ] Video tutorials
- [ ] API documentation
- [ ] Deployment guide

---

## Quick Wins

Small improvements with high impact.

- [ ] Loading states for async operations
- [ ] Empty state improvements
- [ ] Tooltips on all buttons
- [ ] Confirmation dialogs for destructive actions
- [ ] Better error messages
- [ ] Block type icons in palette
- [ ] Collapsible block palette categories
- [ ] Zoom controls for preview
- [ ] Full-screen preview mode
- [ ] Template export/import as JSON

---

## Technical Debt

Things to clean up.

- [ ] Extract shared expression evaluation logic
- [ ] Create reusable toolbar button component
- [ ] Consolidate Tiptap node styling
- [ ] Add proper TypeScript strict mode
- [ ] Remove unused dependencies
- [ ] Code splitting for smaller bundles
- [ ] Add ESLint rules
- [ ] Set up Prettier
- [ ] Add pre-commit hooks

---

## Suggested Order of Implementation

1. **Sandboxed expressions** - Security is non-negotiable
2. **Undo/redo** - Basic usability expectation
3. **Template persistence** - Can't use without saving
4. **Test data management** - Users need to customize data
5. **Table block** - Most requested feature for documents
6. **PDF integration** - See actual output
7. **Page settings** - Control output format
8. **Testing** - Before more features, ensure stability
9. **Image block** - Common need
10. **Row/column layout** - Enable complex layouts

Everything else can be prioritized based on user feedback.
