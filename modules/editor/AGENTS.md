# modules/editor

The Lit + ProseMirror editors — template, theme and data contract — in TypeScript, built with Vite
and packaged into the app as `META-INF/resources/editor`. No React.

Two different jobs live here, with different rules: **document blocks** an author places in a
template, and **editor chrome** (panels, inspectors, dialogs). The `editor-component` skill walks
through both; this guide is the short version.

## Document blocks

A block is not done when it renders on the canvas — it has to survive into a PDF and be
demonstrable. Four things move together:

1. `components/<name>/<name>-registration.ts` exporting `create<Name>Definition(): ComponentDefinition`.
2. Its import in `engine/registry.ts`, the barrel every registration goes through.
3. A `<Name>NodeRenderer.kt` in `modules/generation/.../pdf/`, plus any `RenderingDefaults.kt` entry.
   A block the renderer does not know about silently disappears from output.
4. A realistic use in the demo catalog, with that catalog's version and fingerprint bumped.

**`examples[]` is a PR blocker**: at least one self-contained
`{ name, description, fragment: { rootNodeId, nodes, slots } }`. `registry-examples.test.ts` fails
without it, and the MCP server serves these as the canonical usage. `components/qrcode/` is the
worked example.

**The contract owns the vocabulary.** `pnpm build` runs `check-component-registry.mjs`, which fails
when the editor's projection differs from the contract's registry, so changing a block's _shape_
needs a contract release ([`docs/component-registry.md`](../../docs/component-registry.md)).

## Editor chrome

Lit elements in `ui/`, light DOM (`override createRenderRoot() { return this }`), tag prefixed
`epistola-`, class `Epistola*`. Two current patterns: subscription (hold `engine`, subscribe in
`connectedCallback`, re-subscribe when it changes, unsubscribe in `disconnectedCallback` — see
`ui/EpistolaPreview.ts`) and prop-reactive (everything through `@property`, stateless — see
`ui/EpistolaInspector.ts`). Go through `engine/commands.ts` and `engine/events.ts` rather than
mutating the document directly.

## Layout

Per-component code, CSS and tests sit together in `components/<name>/`; shared styles in
`styles/`; tests co-located as `*.test.ts` and run by vitest. There is no
`src/main/resources/static/css/`.

Note that `*.test.ts` is excluded from `tsc -b` and vitest does not type-check, so test files are
not type-checked today `(unenforced)`.

## Verify

```bash
pnpm build                       # tsc, vite, dump-registry, check-component-registry
pnpm --filter @epistola/editor test
```
