# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
- **Table Configuration Popup**: Dedicated modal interface for table structure management
  - Visual grid designer with cell selection (click, shift-click, ctrl-click)
  - Add/remove rows and columns with visual controls
  - Cell merging and splitting (colspan/rowspan support)
  - Toggle header rows
  - Border style options (none, all, horizontal, vertical)
  - Apply/Cancel workflow for safe configuration changes
  - Reusable `Modal` component with ESC/backdrop close and configurable opacity
- **Columns Block**: Multi-column layouts with ratio-based sizing
  - Dynamic add/remove columns
  - Flexbox-based layout with customizable gap
  - Each column can contain any blocks
- **Table Block**: Structured tables with configurable grid
  - Each cell is a droppable zone for any blocks
  - Support for cell merging (colspan/rowspan)
  - Header row styling
  - Configurable borders
- **Composite ID System**: Support for nested droppable zones using `::` separator
  - Enables proper drag-and-drop for columns and table cells
  - Format: `blockId::columnId` for columns, `blockId::rowId::cellId` for tables

### Changed
- **Table Block**: Simplified main component by removing inline controls
  - Removed scattered add/remove row/column buttons
  - Removed hover-based controls
  - Removed inline colspan/rowspan inputs
  - Added single "Configure Table" button that opens modal
- Updated documentation (architecture.md) to include:
  - Columns and Table blocks in block types table
  - Detailed sections for Columns Block and Table Block features
  - Table Configuration Architecture diagram
  - Composite IDs explanation
  - Updated directory structure
  - Marked Table Block as implemented in potential improvements

### Fixed
- Fixed UUID separator conflict by changing from `-` to `::` for composite IDs
- Fixed modal backdrop opacity using inline rgba() instead of Tailwind utilities
- Fixed modal content padding to ensure proper spacing

## Previous Work

### Expression Evaluation System
- Direct evaluator using `new Function()`
- Sandboxed iframe evaluator for security
- Switchable evaluators at runtime
- 1-second timeout for infinite loop protection

### Core Editor Features
- Rich text editing with Tiptap
- Drag and drop block system with dnd-kit
- Expression system with autocomplete
- Conditional blocks (IF/IF NOT)
- Loop blocks with scoped variables
- Live preview rendering
- Comprehensive styling system with inheritance
- Document and block-level styles
