# Demo Templates

This directory contains template definitions for the demo tenant.

## File Structure

```
demo/
├── templates/
│   ├── invoice.json       # Invoice template with line items
│   └── ...                # Future templates
└── README.md             # This file
```

## Template Definition Format

Each JSON file should contain a complete template definition:

```json
{
  "name": "Template Name",
  "dataModel": { ... },           // JSON Schema for input validation
  "dataExamples": [ ... ],        // Test data examples
  "templateModel": { ... }        // Visual layout (blocks, styles)
}
```

### Data Model (JSON Schema)

Defines the expected input data structure:
- Use standard JSON Schema Draft 7
- Include descriptions for documentation
- Mark required fields
- Use format hints (email, date, etc.)

### Data Examples

Array of test data examples:
- Each example must have: `id`, `name`, `data`
- Data must validate against dataModel schema
- Provide 2-3 examples showing variations

### Template Model

Visual layout structure:
- `pageSettings`: Paper size, margins, orientation
- `documentStyles`: Global font, colors, spacing
- `blocks`: Array of Block objects (text, loop, table, etc.)

## Adding New Templates

1. Create a new JSON file in `demo/templates/`
2. Follow the template definition format above
3. Validate JSON syntax
4. Test by bumping `DEMO_VERSION` in `DemoLoader.kt`
5. Restart application to reload demo

## Template Features

The invoice template demonstrates:
- **Columns**: Multi-column layouts
- **Containers**: Grouped sections with styling
- **Tables**: Header rows + dynamic data rows
- **Loops**: Iterate over arrays
- **Conditionals**: Show/hide blocks based on data
- **Expressions**: SimplePath and JSONata evaluation
- **Styling**: Colors, spacing, fonts, borders

## Version Management

Templates are versioned using `DEMO_VERSION` constant in `DemoLoader.kt`:
- Bump version to trigger demo tenant recreation
- All templates are reloaded from JSON files
- Previous demo tenant is deleted (cascade deletes all data)

Current version: **2.0.1**
