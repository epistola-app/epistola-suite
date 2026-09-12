---
name: editor-component
description: Add or change something in the template editor — a document block authors place in a template, or editor chrome like a panel or dialog. Use for both; they have different rules.
---

Two different jobs live behind "editor component". Decide which one you are doing first.

| You are adding                                                             | Path                                                  | Exemplar                                                   |
| -------------------------------------------------------------------------- | ----------------------------------------------------- | ---------------------------------------------------------- |
| A **document block** an author places in a template (qrcode, table, image) | registration → registry → PDF renderer → demo catalog | `modules/editor/src/main/typescript/components/qrcode/`    |
| **Editor chrome** — a panel, inspector, dialog                             | a Lit element under `ui/`                             | `modules/editor/src/main/typescript/ui/EpistolaPreview.ts` |

## A document block

A block is not finished when it renders on the canvas. It has to survive the round trip into a PDF
and be demonstrable, so four things move together:

1. **Registration** — `components/<name>/<name>-registration.ts` exports
   `create<Name>Definition(): ComponentDefinition`: `type`, `label`, `icon`, `category`, `slots`,
   `allowedChildren`, `applicableStyles`, `defaultStyles`, `inspector`, `defaultProps`,
   `renderCanvas`, and `examples`.
2. **Registry** — import that factory in `engine/registry.ts`, which is the barrel every registration
   is wired through.
3. **PDF renderer** — a `<Name>NodeRenderer.kt` in
   `modules/generation/src/main/kotlin/app/epistola/generation/pdf/`, plus any entry in
   `RenderingDefaults.kt`. A block the renderer does not know about silently disappears from output.
4. **Demo catalog** — use it in `apps/epistola-demo/src/main/resources/epistola/catalogs/demo/`
   (qrcode appears in `resources/templates/demo-invoice.json`), then bump that catalog's
   `release.version` **and** regenerate `release.fingerprint`.

**`examples[]` is a PR blocker.** At least one entry, each a self-contained
`{ name, description, fragment: { rootNodeId, nodes, slots } }` showing a realistic use — the MCP
server and the design docs serve these as the canonical usage. `qrcode-registration.ts` has two: one
data-bound, one literal.

**The contract owns the vocabulary.** `pnpm build` runs `check-component-registry.mjs`, which fails
when the editor's projection differs from the contract's registry, so changing a block's _shape_
needs a contract release — see [`docs/component-registry.md`](../../../docs/component-registry.md)
and the `contract-bump` skill. Adding runtime behaviour behind an existing shape does not.

## Editor chrome

- Lit elements in `ui/`, light DOM (`override createRenderRoot() { return this }`), tag prefixed
  `epistola-`, class `Epistola*`.
- Two patterns, both current: **subscription** — hold `engine`, subscribe in `connectedCallback`,
  re-subscribe when the `engine` property changes, unsubscribe in `disconnectedCallback`
  (`ui/EpistolaPreview.ts`); **prop-reactive** — take everything through `@property` and stay
  stateless (`ui/EpistolaInspector.ts`).
- Engine commands live in `engine/commands.ts`, events in `engine/events.ts`. Go through them rather
  than mutating the document directly.

## Where files go

| What                                                   | Where                                                   |
| ------------------------------------------------------ | ------------------------------------------------------- |
| Block registration, its preview element, CSS and tests | `modules/editor/src/main/typescript/components/<name>/` |
| Editor chrome                                          | `modules/editor/src/main/typescript/ui/`                |
| Engine (registry, commands, events)                    | `modules/editor/src/main/typescript/engine/`            |
| Shared styles                                          | `modules/editor/src/main/typescript/styles/`            |
| Tests                                                  | co-located `*.test.ts` (vitest)                         |

Per-component CSS sits beside the component (`components/qrcode/qrcode.css`). There is no
`src/main/resources/static/css/` — an older guide claimed there was.

## Verify

```bash
pnpm build                       # tsc, vite, dump-registry, check-component-registry
pnpm --filter @epistola/editor test
./gradlew :apps:epistola-demo:unitTest --tests "*DemoCatalogFingerprintTest"   # if you touched the demo catalog
```

`registry-examples.test.ts` fails when a definition has no `examples[]`; the MCP component tests and
`ExampleRenderingIntegrationTest` render each example.
