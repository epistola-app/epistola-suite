# Create-dialog error handling — a visual guide

A diagram-first companion to [ADR 0011](adr/0011-create-form-validation-errors.md) and
[`docs/htmx.md`](htmx.md) → "Create-form error handling". Read those for the prose and the
rationale; read this to see the shapes.

Every "create new" flow opens in a shared modal `<dialog>`. When a submit fails, **where** the
error renders is decided by one question: _is the problem tied to a specific field, or not?_

---

## 1. The core split: field vs. non-field

```mermaid
flowchart TD
    submit["User submits a create dialog"] --> q{"Is the problem tied<br/>to a specific field?"}

    q -->|"YES — field-keyable<br/>(required, bad format,<br/>invalid JSON, duplicate id)"| field["Field error = <b>data</b><br/>(an errors map keyed by field name)"]
    q -->|"NO — operational / server<br/>(backend rejection, read-only<br/>catalog, unexpected failure)"| thrown["<b>Thrown</b><br/>(FormInputException or<br/>any domain exception)"]

    field --> spanpath["Render <b>next to the field</b><br/>(its own .form-error span)"]
    thrown --> cardpath["Render in the shared<br/><b>#dialog-error</b> card<br/>(top of the dialog)"]

    classDef yes fill:#e6f4ea,stroke:#34a853;
    classDef no fill:#fce8e6,stroke:#ea4335;
    class field,spanpath yes;
    class thrown,cardpath no;
```

The rule of thumb: **a field problem belongs beside its field; the shared card is reserved for
problems that aren't about any single field.**

---

## 2. Three delivery mechanisms

Field errors are always "data," but _how_ they reach the page depends on whether the server can
re-render the whole form without destroying in-progress state. That splits the create dialogs into
two families. Thrown errors take a third, central path.

```mermaid
flowchart TD
    subgraph FIELD["Field error (data) — rendered next to the field"]
        direction TB
        f["Field error"] --> recon{"Can the server reconstruct<br/>the whole form?"}
        recon -->|"YES — text forms (8):<br/>templates · themes · api-keys ·<br/>environments · stencils · attributes ·<br/>code-lists · catalogs"| selfswap["<b>Self-swap</b> the whole createForm<br/>hx-target=this, hx-swap=outerHTML<br/>→ the field spans come back with it"]
        recon -->|"NO — file / cascade forms (3):<br/>fonts · assets · load-test"| oob["<b>Per-field OOB spans</b><br/>200 + HX-Reswap: none<br/>→ only the spans swap;<br/>file / selections / typed JSON survive"]
    end

    subgraph GENERAL["Non-field error (thrown) — rendered in the shared card"]
        direction TB
        g["Thrown exception"] --> resolver["UiHandlerExceptionResolver<br/>(resolveUiError → message + status)"]
        resolver --> acc{"How did the caller ask?"}
        acc -->|"HTMX dialog<br/>(X-Epistola-Error-Region header)"| dcard["OOB swap into <b>#dialog-error</b><br/>inside the modal<br/>(200 + HX-Reswap: none)"]
        acc -->|"Data caller<br/>(Accept: application/json)"| pj["RFC 9457 <b>problem+json</b><br/>(detail carries the message)"]
    end

    note["UiExceptionFilter is the last-resort net<br/>for exceptions that escape the dispatch<br/>(data/error-page, never markup)"]
    resolver -.-> note
```

Why the file/cascade forms differ: a `<input type=file>` value can't be repopulated by the server,
and the load-test cascade selections + typed JSON can't be cheaply rebuilt — so re-rendering the
whole form would wipe what the user already did. The OOB path swaps **only** the error spans and
leaves everything else alone.

---

## 3. Sequence — a field error (load-test invalid JSON)

The case we most recently fixed: bad JSON in the load-test dialog renders **under the textarea**,
not as a banner, and nothing the user typed or selected is lost.

```mermaid
sequenceDiagram
    actor U as User
    participant H as HTMX (browser)
    participant LH as LoadTestHandler.start

    U->>H: Submit (testData = invalid JSON)
    H->>LH: POST /tenants/{id}/load-tests
    Note over LH: parse + validate fields, record a testData field error
    LH-->>H: 200, HX-Reswap none, plus error-fields OOB spans
    Note over H: primary target skipped, OOB swaps the testData error span in place
    H-->>U: Error under the JSON field, selections + typed JSON intact
```

The same shape applies to `templateId` / `variantId` / `versionId` and to the fonts/assets
per-field errors — only the field id changes.

---

## 4. Sequence — a non-field (operational) error

When the field values are fine but the operation itself fails (e.g. the backend rejects the
load-test start), the handler **throws**, and the central resolver renders it into the card
_inside_ the modal.

```mermaid
sequenceDiagram
    actor U as User
    participant H as HTMX (browser)
    participant Hd as Handler
    participant R as UiHandlerExceptionResolver

    U->>H: Submit (all fields valid)
    H->>Hd: POST (carries header X-Epistola-Error-Region = dialog-error)
    Note over Hd: operation fails
    Hd->>R: throw FormInputException
    Note over R: resolveUiError maps it to a message + status
    R-->>H: 200, HX-Reswap none, dialogError OOB into the dialog-error region
    H-->>U: Alert in the card at the top of the dialog
```

The `X-Epistola-Error-Region` header is set on the `<dialog>` via `hx-headers`, so it rides along
on the form submit and tells the resolver which region to target. It's absent on every other HTMX
flow → zero blast radius.

---

## 5. How the border is drawn (one CSS rule, no server-toggled classes)

Every error span is **always** in the markup; styling is a pure function of its `data-error`
attribute, so the same rule works for a whole-form re-render and an OOB span swap.

```mermaid
flowchart LR
    a["span.form-error (always rendered)<br/>data-error = errors.containsKey(field)"] --> b{"data-error == 'true'?"}
    b -->|yes| c[":has() rule borders the<br/>input / select / textarea<br/>(--ep-destructive) + message shown"]
    b -->|"no / absent"| d[".form-error:empty → display:none<br/>(no border, no gap)"]
```

```css
.form-group:has(.form-error[data-error="true"]) input,
.form-group:has(.form-error[data-error="true"]) textarea,
.form-group:has(.form-error[data-error="true"]) select {
  border-color: var(--ep-destructive);
}
.form-error:empty {
  display: none;
}
```

---

## 6. The convention, and how it's enforced

Both error surfaces are a **standing rule** for every create dialog — checked at build time by
`CreateDialogErrorConventionTest` (in `unitTest`), so a regression fails CI instead of silently
dropping a message.

```mermaid
flowchart TD
    pr["New or changed create dialog<br/>(templates/&lt;entity&gt;/new.html)"] --> test{"CreateDialogErrorConventionTest"}

    test -->|"✓ every payload field has a per-field span<br/>✓ #dialog-error region + X-Epistola-Error-Region header present"| pass["✅ build passes"]
    test -->|"✗ a payload field is missing its span<br/>✗ or the general region/header is missing"| fail["❌ build fails<br/>(lists every gap)"]

    classDef ok fill:#e6f4ea,stroke:#34a853;
    classDef bad fill:#fce8e6,stroke:#ea4335;
    class pass ok;
    class fail bad;
```

**Exempt** (not part of the create payload, so they can't produce a field error): file inputs,
radio/checkbox choice groups, and a cascade-only helper select (load-test's `exampleId`). Each
exemption is listed with its reason in the test.

> This was a real bug, not just tidiness: five forms (templates, themes, stencils, attributes,
> code-lists) keyed a "Catalog is required" error to a `catalog` field that had **no span**, so the
> message silently vanished. The test makes that class of bug impossible to merge.

---

## At a glance — which form uses which path

| Dialog       | Field-error delivery   | General errors                        |
| ------------ | ---------------------- | ------------------------------------- |
| templates    | self-swap (whole form) | `#dialog-error` card                  |
| themes       | self-swap              | `#dialog-error` card                  |
| api-keys     | self-swap              | `#dialog-error` card                  |
| environments | self-swap              | `#dialog-error` card                  |
| stencils     | self-swap              | `#dialog-error` card                  |
| attributes   | self-swap              | `#dialog-error` card                  |
| code-lists   | self-swap              | `#dialog-error` card                  |
| catalogs     | self-swap              | `#dialog-error` card                  |
| fonts        | per-field OOB spans    | `#dialog-error` card                  |
| assets       | per-field OOB spans    | `#dialog-error` card / `problem+json` |
| load-test    | per-field OOB spans    | `#dialog-error` card                  |

The asset endpoint is the one that also answers `problem+json`, because the editor calls it
non-HTMX with `Accept: application/json`.
