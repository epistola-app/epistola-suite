<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0008: Page header/footer `height` is a minimum (auto-grow)

- **Status:** Accepted
- **Date:** 2026-06-24
- **Deciders:** Epistola team
- **Tags:** generation, rendering, pdf, headers, footers

## Context

A `pageheader` / `pagefooter` node carries an optional `height` prop (e.g.
`"60pt"`). In the PDF renderer (`DirectPdfRenderer`) a header/footer is drawn as
an iText overlay into a **fixed `Canvas` rectangle of exactly that height**, and
the band reserved in the body margin was the same fixed value.

iText **discards** content that overflows a `Canvas` rectangle. So a header sized
smaller than its own content rendered **blank** — the content silently vanished,
the reserved band stayed. This only bit content-heavy headers (a letterhead with
a logo, an address block, several lines), which is why it was hard to reproduce
with a plain-text header and surfaced to a customer as "I change the header
height and nothing happens."

The underlying ambiguity is what `height` _means_. Treating it as an exact
clip is what produced the data loss. The decision is which semantic to commit to,
because it is **user-facing** and has downstream consequences (editor wording,
two-header layout, address-block interaction, demo coverage).

## Considered options

1. **Keep the fixed clip (status quo).** Simplest, but silently drops content —
   the reported bug. Rejected.
2. **Clip, but warn/error on overflow.** Surfaces the problem instead of hiding
   it, but still doesn't render the content; the author must hand-tune `height`
   to the content, and the warning has nowhere good to surface during a preview.
   Rejected as the primary behaviour.
3. **Reject overflow at save/validation time** (constrain authoring so a header
   can never be smaller than its content). Requires measuring content at
   authoring time (the editor has no PDF layout engine), couples validation to
   the renderer, and still forces manual sizing. Rejected.
4. **`height` is a minimum; the band auto-grows to fit content.** The renderer
   measures each header/footer's natural content height (an iText dry-layout
   pre-pass) and reserves `max(configured height, content height)`. Content is
   never lost; increasing `height` adds whitespace; content shorter than it is
   unaffected. Costs one extra measurement pass per render (only when a
   header/footer exists).
5. **Always auto-size; drop the `height` prop entirely.** Removes the control
   authors use to reserve deliberate whitespace / a fixed band, and is a larger
   model change. Rejected; `height`-as-minimum keeps the control while fixing the
   data loss.

## Decision

Adopt option 4: **`height` is a minimum, and the band auto-grows to fit its
content.** The renderer pre-measures each header and footer via a throwaway
dry layout (own `PdfDocument` + `FontCache`, since iText fonts are
document-bound) and reserves `max(configured, measured)` for both the drawn
rectangle and the body margin, with `OVERFLOW_VISIBLE` as a defensive safety net
and a measurement-failure fallback to the configured height so the pre-pass can
never make a previously-working render fail.

Two coupled decisions fall out of the same change, because the customer template
that surfaced the bug nests an `addressblock` inside a header:

- The address block (a page-absolute element with a body-side window
  reservation) is **hoisted to the body root before the bands are measured and
  rendered**, so it never renders inside — and inflates — a band.
- Its body reservation is computed **relative to the effective header height**
  (`RenderContext.bodyContentTopPt`), so a tall header shrinks the reservation
  instead of always reserving the full window height.

The mechanism is documented in [`docs/generation.md`](../generation.md) under
"Header & footer band height (auto-grow)" and "Address blocks vs. header/footer
bands".

## Consequences

- **Content is never silently dropped** from a header/footer; the reported bug is
  fixed on all render surfaces (web preview, REST generation, MCP preview share
  the renderer).
- **Behaviour change for under-sized headers/footers:** a band previously
  clipped to a too-small `height` now grows. Acceptable (the prior behaviour was
  the bug), but it is a visible change for any template that relied on a small
  height to crop a band.
- **Cost:** one extra dry-layout pass per render, bounded to the (≤2) headers +
  footer, run once per render (not per page). Skipped entirely for documents with
  no header/footer.
- **Editor follow-up:** the inspector field labelled "Height" should be relabelled
  "Min height" so the wording matches the semantic (tracked as a follow-up).
- **Out of scope / known limitations:** two-header coupling for the
  cover-shorter-than-running case (still an additive page-1 spacer); an address
  block authored inside a header can still geometrically overlap it (address
  blocks are body elements — the renderer no longer breaks, but doesn't make that
  authoring correct); demo-catalog coverage for a tall-content header.
