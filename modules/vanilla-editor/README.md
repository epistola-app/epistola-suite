# @epistola/vanilla-editor

Package-owned editor UI built on `@epistola/headless-editor`.

- Mounts a full editor shell from one container
- Uses plugin-driven toolbar block catalog
- Includes block canvas rendering, drag-drop integration, and style modals

## Install

```bash
pnpm add @epistola/vanilla-editor @epistola/headless-editor
```

## Main API

Use `mountEditorApp(...)`.

```ts
import { mountEditorApp } from '@epistola/vanilla-editor';

const mounted = mountEditorApp({
  container: '#editor-root',
  template: {
    id: 'tmpl-1',
    name: 'My Template',
    blocks: [],
  },
  onSave: async (template) => {
    await fetch('/templates/save', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(template),
    });
  },
  onPreview: async (template, data) => {
    const response = await fetch('/templates/preview', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ template, data }),
    });

    if (!response.ok) throw new Error('Preview failed');
    return await response.blob();
  },
});

// later
mounted.destroy();
```

## Config Reference

`MountEditorAppConfig` supports:

- `container`: selector or element
- `template`: initial template
- `onSave?`: async save callback
- `onPreview?`: async preview callback returning `Blob`, HTML `string`, `{ mimeType, data }`, or `void`
- `themes?`, `defaultTheme?`
- `dataExamples?`
- `schema?`
- `debug?`
- `ui?` (`showThemeSelector`, `showDataExampleSelector`, `showPreview`, labels)
- `rendererPlugins?`: UI renderer plugins for this instance

## Plugin-Driven Toolbar

Add buttons are generated from headless block catalog metadata.

To hide internal blocks (for example structural-only blocks), set:

```ts
toolbar: false
```

Or customize visibility/group/order/label/icon via block definition `toolbar` config.

## Renderer Plugins (Per-Instance)

You can provide renderer plugins directly in mount config.

```ts
mountEditorApp({
  container: '#editor-root',
  template,
  rendererPlugins: [
    {
      type: 'image',
      render: ({ block }) => {
        // return uhtml-compatible output
        return null;
      },
    },
  ],
});
```

## Styling Behavior

- Resolved styles are applied to `.block-content`.
- Editor chrome (`.block-header`, controls, drag handles, etc.) remains outside content style cascade.
- Cascade behavior comes from headless: `document -> ancestors -> block`.

## Built-In Shell Features

The mounted app shell includes:

- Plugin-driven add toolbar
- Undo/redo/save controls
- Optional preview trigger
- Theme/data-example selectors (toggleable)
- Page settings modal
- Document styles modal
- Block styles modal

## Plain JavaScript Usage (non-TS)

```js
import { mountEditorApp } from '@epistola/vanilla-editor';

mountEditorApp({
  container: '#editor-root',
  template: { id: 'tmpl-1', name: 'Demo', blocks: [] },
  onSave: async (template) => {
    await fetch('/save', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(template),
    });
  },
});
```

If you want IDE IntelliSense in JS, use `// @ts-check` and JSDoc `import(...)` type annotations.

## Notes

- This package is the UI adapter; domain logic lives in `@epistola/headless-editor`.
- For advanced direct core access, use `mounted.getEditor()`.
