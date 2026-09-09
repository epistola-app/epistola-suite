# Historical implementation plans

> **Status:** Record. These are the plans that were written before a piece of
> work started. They are kept for the reasoning, the alternatives weighed, and
> the migration steps — **not** as a description of the system today. Module
> names, file paths, and phase checklists in them are frequently out of date.

For current documentation start at the [documentation index](../README.md). For
the decisions that came out of this work, see the
[architecture decision records](../adr/README.md).

| Plan                                                                   | Subject                                               |
| ---------------------------------------------------------------------- | ----------------------------------------------------- |
| [architecture.md](architecture.md)                                     | Command pattern with event-driven extensions.         |
| [catalog-wire-format-migrations.md](catalog-wire-format-migrations.md) | EF-style migrations for the catalog wire format.      |
| [default-variant.md](default-variant.md)                               | Making the default template variant an explicit flag. |
| [editor-rewrite.md](editor-rewrite.md)                                 | The Lit + ProseMirror editor rewrite (v2).            |
| [editor-v2-integration.md](editor-v2-integration.md)                   | Cutting the app over from the v1 editor to v2.        |
| [flow-anchored-running-headers.md](flow-anchored-running-headers.md)   | Single-pass running headers with dynamic height.      |
| [phase-4-rich-text.md](phase-4-rich-text.md)                           | ProseMirror integration for rich text in the editor.  |
| [tenant-scoped.md](tenant-scoped.md)                                   | Tenant-scoped composite primary keys.                 |
| [todo.md](todo.md)                                                     | A scratch list of themes to pick up.                  |
| [ui-redesign-verification.md](ui-redesign-verification.md)             | Verification checklist for the UI redesign.           |
| [undo-redo.md](undo-redo.md)                                           | `TextChange` as a first-class undo entry.             |
